package com.boucle.app.privacy

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.screens.SetupFilledButton
import com.boucle.app.ui.screens.SetupOutlinedButton
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleTheme
import com.boucle.app.ui.theme.BoucleType

/**
 * Explication de l'usage des données Health Connect, ouverte par Health Connect lui-même :
 * `ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13) et `VIEW_PERMISSION_USAGE` (Android 14 et plus).
 * Le résumé est embarqué pour rester lisible hors connexion ; la politique complète s'ouvre dans le navigateur.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BoucleTheme {
                PrivacyPolicyScreen(onOpenFullPolicy = ::openFullPolicy, onClose = ::finish)
            }
        }
    }

    private fun openFullPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, POLICY_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            // Aucun navigateur : le résumé affiché reste la seule source, rien d'autre à faire.
        }
    }

    companion object {
        const val POLICY_URL = "https://adrienmu.github.io/watch-connect/confidentialite/"
        const val TERMS_URL = "https://adrienmu.github.io/watch-connect/conditions/"
    }
}

/** Donnée lue dans Health Connect et ce qu'elle permet dans l'app (doit suivre `docs/confidentialite.md`). */
internal val healthConnectUsages = listOf(
    "Poids" to "écran Corps : dernière pesée, courbe sur 30 jours, évolution sur 7 et 30 jours.",
    "Masse grasse, masse maigre, masse osseuse, eau corporelle" to "écran Corps, carte « Composition corporelle ».",
    "Saturation en oxygène" to "écran Corps, carte « SpO2 · nuit » (moyenne et minimum).",
    "Variabilité de la fréquence cardiaque" to "scores de récupération, de stress et de bien-être.",
    "Fréquence cardiaque, sommeil, fréquence respiratoire" to
        "écrans Cœur et Sommeil, scores de sommeil, de récupération et Énergie (si la montre remplace le bracelet).",
    "Pas, distance, calories actives" to
        "écrans Aujourd'hui et Activité : totaux du jour, de la semaine et de chaque séance ; score Énergie.",
    "Séances d'exercice et tracés GPS" to
        "écran Activité : détail des séances et carte du parcours (tracé autorisé séance par séance).",
)

@Composable
internal fun PrivacyPolicyScreen(onOpenFullPolicy: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(BoucleColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Vos données de santé dans Boucle",
            style = BoucleType.detailTitle,
            color = BoucleColors.OnSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Boucle lit dans Health Connect, sans jamais rien y écrire, les mesures que l'app Withings y enregistre, " +
                "pour les afficher et calculer vos scores sur ce téléphone. Rien n'est envoyé à un serveur, " +
                "partagé, vendu ou utilisé pour la publicité.",
            style = BoucleType.bodyLarge,
            color = BoucleColors.OnSurface,
        )

        Section("À quoi sert chaque donnée") {
            healthConnectUsages.forEach { (data, usage) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(data, style = BoucleType.bodyStrong, color = BoucleColors.OnSurface)
                    Text(usage, style = BoucleType.body, color = BoucleColors.OnSurfaceVariant)
                }
            }
        }

        Section("Conservation") {
            Paragraph(
                "Les données restent dans l'espace privé de l'application, sur ce téléphone uniquement, tant que " +
                    "l'application est installée. Désinstaller Boucle ou effacer ses données (Réglages > Applications > " +
                    "Boucle > Stockage) les supprime définitivement. Boucle ne conserve rien ailleurs.",
            )
        }

        Section("Retirer votre accord") {
            Paragraph(
                "Réglages > Health Connect > Autorisations des applications > Boucle. " +
                    "Boucle arrête alors toute lecture.",
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupFilledButton(
                text = "Lire la politique complète",
                onClick = onOpenFullPolicy,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
            )
            SetupOutlinedButton(
                text = "Fermer",
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
                textStyle = BoucleType.bodyStrong,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BoucleColors.Surface, BoucleShapes.cardSmall)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = BoucleType.sectionTitle, color = BoucleColors.OnSurface, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = BoucleType.body, color = BoucleColors.OnSurface)
}
