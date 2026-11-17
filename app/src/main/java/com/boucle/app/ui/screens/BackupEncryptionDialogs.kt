package com.boucle.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boucle.app.data.backup.BackupCrypto
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette

/*
 * Saisie de la phrase de passe des sauvegardes chiffrées.
 *
 * Limite connue : les champs de texte Compose travaillent sur des String, qu'on ne peut pas effacer de la mémoire.
 * La saisie est donc gardée le moins longtemps possible : jamais dans l'état sauvegardé (remember, pas
 * rememberSaveable, pour qu'elle ne soit pas écrite sur le stockage à la mise en arrière-plan), convertie en
 * CharArray à la validation (effacé par le modèle de vue après dérivation de la clé) et vidée des champs aussitôt.
 */

/** Dialogue de chiffrement ouvert depuis la ligne « Sauvegarde automatique ». */
internal enum class BackupDialog { Enable, Encrypt, Change, Reenter, Decrypt }

private const val IRRECOVERABLE_WARNING =
    "Sans cette phrase de passe, les sauvegardes chiffrées sont irrécupérables : Boucle ne la garde nulle part et " +
        "personne ne peut la retrouver. Note-la en lieu sûr, ailleurs que sur ce téléphone."

@Composable
internal fun BackupEncryptionDialogs(
    dialog: BackupDialog,
    onEnable: (passphrase: CharArray?) -> Unit,
    onSetPassphrase: (CharArray) -> Unit,
    onDisableEncryption: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        BackupDialog.Enable -> PassphraseDialog(
            title = "Activer la sauvegarde automatique",
            intro = "Choisis ensuite le dossier où Boucle écrira ses sauvegardes. Le chiffrement est conseillé : " +
                "ces fichiers contiennent tes données de santé, et le dossier n'est pas protégé par l'app.",
            confirmLabel = "Choisir le dossier",
            offerPlain = true,
            onConfirm = onEnable,
            onDismiss = onDismiss,
        )
        BackupDialog.Encrypt -> PassphraseDialog(
            title = "Chiffrer les sauvegardes",
            intro = "Les prochaines sauvegardes seront chiffrées avec cette phrase de passe. Celles déjà écrites en " +
                "clair restent dans le dossier jusqu'à leur remplacement par la rotation.",
            confirmLabel = "Chiffrer",
            offerPlain = false,
            onConfirm = { it?.let(onSetPassphrase) },
            onDismiss = onDismiss,
        )
        BackupDialog.Change -> PassphraseDialog(
            title = "Changer la phrase de passe",
            intro = "Les prochaines sauvegardes utiliseront la nouvelle phrase de passe. Les sauvegardes déjà écrites " +
                "gardent l'ancienne : garde-la tant que tu pourrais en avoir besoin.",
            confirmLabel = "Changer",
            offerPlain = false,
            onConfirm = { it?.let(onSetPassphrase) },
            onDismiss = onDismiss,
        )
        BackupDialog.Reenter -> PassphraseDialog(
            title = "Ressaisir la phrase de passe",
            intro = "La clé de chiffrement ne voyage pas d'un téléphone à l'autre. Saisis la phrase de passe pour " +
                "reprendre les sauvegardes chiffrées. Si tu en choisis une nouvelle, seules les prochaines " +
                "sauvegardes l'utiliseront.",
            confirmLabel = "Enregistrer",
            offerPlain = false,
            onConfirm = { it?.let(onSetPassphrase) },
            onDismiss = onDismiss,
        )
        BackupDialog.Decrypt -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Ne plus chiffrer ?", style = BoucleType.sectionTitle) },
            text = {
                Text(
                    "Les prochaines sauvegardes seront écrites en clair (.zip) : quiconque accède au dossier pourra " +
                        "les lire. Les sauvegardes déjà chiffrées le restent, avec leur phrase de passe.",
                    style = BoucleType.body,
                )
            },
            confirmButton = { DevicesTextButton("Ne plus chiffrer", onDisableEncryption, color = BoucleColors.Error) },
            dismissButton = { DevicesTextButton("Annuler", onDismiss) },
            containerColor = BoucleColors.Surface,
            titleContentColor = BoucleColors.OnSurface,
            textContentColor = BoucleColors.OnSurfaceVariant,
        )
    }
}

/**
 * Choix d'une phrase de passe : saisie, confirmation, longueur minimale et avertissement. Avec [offerPlain], un
 * interrupteur (activé par défaut) permet de continuer sans chiffrement ; [onConfirm] reçoit alors `null`.
 */
@Composable
private fun PassphraseDialog(
    title: String,
    intro: String,
    confirmLabel: String,
    offerPlain: Boolean,
    onConfirm: (CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var encrypt by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    // Vide les champs quand le dialogue disparaît, quelle qu'en soit la raison.
    DisposableEffect(Unit) {
        onDispose {
            passphrase = ""
            confirmation = ""
        }
    }
    val tooShort = passphrase.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = passphrase != confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = BoucleType.sectionTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(intro, style = BoucleType.body)
                if (offerPlain) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = encrypt, role = Role.Switch, onValueChange = { encrypt = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Chiffrer les sauvegardes", style = BoucleType.bodyStrong, color = BoucleColors.OnSurface, modifier = Modifier.weight(1f))
                        Switch(checked = encrypt, onCheckedChange = null)
                    }
                }
                if (encrypt) {
                    ProfileTextField(
                        label = "Phrase de passe",
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        helper = if (showErrors && tooShort) {
                            "Au moins ${BackupCrypto.MIN_PASSPHRASE_LENGTH} caractères."
                        } else {
                            "Au moins ${BackupCrypto.MIN_PASSPHRASE_LENGTH} caractères ; une phrase de plusieurs mots est idéale."
                        },
                    )
                    ProfileTextField(
                        label = "Confirmer la phrase de passe",
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        helper = if (showErrors && !tooShort && mismatch) "Les deux saisies ne correspondent pas." else null,
                    )
                    Text(IRRECOVERABLE_WARNING, style = BoucleType.caption, color = MetricPalette.Energy.accent)
                } else {
                    Text(
                        "Sans chiffrement, les sauvegardes sont des archives .zip lisibles par quiconque accède au dossier.",
                        style = BoucleType.caption,
                        color = MetricPalette.Energy.accent,
                    )
                }
            }
        },
        confirmButton = {
            DevicesTextButton(confirmLabel, {
                if (!encrypt) {
                    onConfirm(null)
                } else if (tooShort || mismatch) {
                    showErrors = true
                } else {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    onConfirm(chars)
                }
            })
        },
        dismissButton = { DevicesTextButton("Annuler", onDismiss) },
        containerColor = BoucleColors.Surface,
        titleContentColor = BoucleColors.OnSurface,
        textContentColor = BoucleColors.OnSurfaceVariant,
    )
}

/** Import d'une sauvegarde chiffrée : saisie de sa phrase de passe, avec « Phrase de passe incorrecte. » au besoin. */
@Composable
internal fun ImportPassphraseDialog(
    checking: Boolean,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { passphrase = "" }
    }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("Importer cette sauvegarde chiffrée ?", style = BoucleType.sectionTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Saisis la phrase de passe avec laquelle elle a été chiffrée. Seules les données absentes de ce " +
                        "téléphone sont ensuite ajoutées, celles déjà présentes sont conservées telles quelles ; le " +
                        "profil, le bracelet et les dates de synchronisation ne " +
                        "sont repris que s'ils ne sont pas déjà renseignés ici.",
                    style = BoucleType.body,
                )
                ProfileTextField(
                    label = "Phrase de passe",
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                )
                when {
                    checking -> Text("Vérification de la phrase de passe…", style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
                    error != null -> Text(error, style = BoucleType.caption, color = BoucleColors.Error)
                }
            }
        },
        confirmButton = {
            DevicesTextButton(if (checking) "Vérification…" else "Importer", {
                if (!checking && passphrase.isNotEmpty()) {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onConfirm(chars)
                }
            }, color = BoucleColors.OnSurface)
        },
        dismissButton = { DevicesTextButton("Annuler", { if (!checking) onDismiss() }) },
        containerColor = BoucleColors.Surface,
        titleContentColor = BoucleColors.OnSurface,
        textContentColor = BoucleColors.OnSurfaceVariant,
    )
}
