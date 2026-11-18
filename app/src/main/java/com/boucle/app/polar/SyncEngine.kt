package com.boucle.app.polar

import com.boucle.app.data.db.HealthDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime

/**
 * Récupère toutes les données du bracelet sur une plage de dates et les range dans Room.
 *
 * Les étapes s'enchaînent strictement une par une (exigence du bracelet) et sont indépendantes :
 * une étape en échec est journalisée et n'empêche pas les suivantes.
 *
 * Le Loop n'a que 15 Mo de mémoire : comme le recommande Polar, les jours déjà copiés sont ensuite effacés
 * du bracelet. Seulement si toutes les étapes ont réussi, seulement les jours lus par cette synchronisation,
 * et jamais les [KEEP_RECENT_DAYS] derniers jours (le sommeil et le Nightly Recharge d'une nuit n'arrivent
 * qu'environ 90 minutes après le réveil, et la journée en cours change encore).
 */
class SyncEngine(
    private val gateway: PolarGateway,
    private val dao: HealthDao,
) {
    /** @return `true` si toutes les étapes ont réussi. */
    suspend fun sync(
        deviceId: String,
        from: LocalDate,
        to: LocalDate,
        log: (String) -> Unit,
    ): Boolean {
        val api = gateway.api
        log("Synchronisation du $from au $to")

        val ready = try {
            api.sendInitializationAndStartSyncNotifications(deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("✗ Préparation du bracelet : ${e.describe()}")
            return false
        }
        if (!ready) {
            log("✗ Le bracelet n'est pas prêt (entraînement en cours ?). Réessaie plus tard.")
            return false
        }

        var allOk = true
        suspend fun step(label: String, block: suspend () -> Int) {
            try {
                log("✓ $label : ${block()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allOk = false
                log("✗ $label : ${e.describe()}")
            }
        }

        try {
            step("Heure du bracelet") {
                api.setLocalTime(deviceId, LocalDateTime.now())
                1
            }
            step("Résumés quotidiens (pas, calories, temps actif)") {
                val steps = api.getSteps(deviceId, from, to).associate { it.date to it.steps }
                val distances = api.getDistance(deviceId, from, to).associate { it.date to it.distanceMeters }
                val entities = api.getDailySummaryData(deviceId, from, to).mapNotNull {
                    it.toEntity(fallbackSteps = steps[it.date], fallbackDistance = distances[it.date])
                }
                dao.upsertDailyActivity(entities)
                entities.size
            }
            step("Pas et MET à la minute") {
                val entities = api.getActivitySampleData(deviceId, from, to).flatMap { it.toEntities() }
                dao.upsertActivitySamples(entities)
                entities.size
            }
            step("Fréquence cardiaque 24/7") {
                val entities = api.get247HrSamples(deviceId, from, to).flatMap { it.toEntities() }
                dao.upsertHrBlocks(entities)
                entities.size
            }
            step("Intervalles PPI (VFC)") {
                val entities = api.get247PPiSamples(deviceId, from, to).map { it.toEntity() }
                dao.upsertPpiBlocks(entities)
                entities.size
            }
            step("Sommeil") {
                val entities = api.getSleep(deviceId, from, to).mapNotNull { it.toEntity() }
                dao.upsertSleep(entities)
                entities.size
            }
            step("Nightly Recharge") {
                val entities = api.getNightlyRecharge(deviceId, from, to).map { it.toEntity() }
                dao.upsertNightlyRecharge(entities)
                entities.size
            }
            step("Température cutanée") {
                val entities = api.getSkinTemperature(deviceId, from, to).mapNotNull { it.toEntity() }
                dao.upsertSkinTemperature(entities)
                entities.size
            }
            step("Entraînements") {
                val references = api.getTrainingSessionReferences(deviceId, from, to).toList()
                references.forEach { reference ->
                    val session = api.getTrainingSession(deviceId, reference)
                    dao.upsertTrainingSessions(listOf(session.toEntity()))
                    dao.upsertTrainingExercises(session.toExerciseEntities())
                    session.toRouteEntity()?.let { dao.upsertTrainingRoutes(listOf(it)) }
                }
                references.size
            }
            val cleanUntil = to.minusDays(KEEP_RECENT_DAYS)
            if (allOk && !from.isAfter(cleanUntil)) {
                step("Nettoyage du bracelet (du $from au $cleanUntil)") {
                    api.deleteDeviceDateFolders(deviceId, from, cleanUntil)
                    (ChronoUnit.DAYS.between(from, cleanUntil) + 1).toInt()
                }
            } else if (!allOk) {
                log("· Bracelet non nettoyé : une étape a échoué, ses données y restent pour la prochaine fois.")
            }
        } finally {
            // Le bracelet doit toujours être sorti du mode synchronisation, même en cas d'annulation.
            withContext(NonCancellable) {
                try {
                    api.sendTerminateAndStopSyncNotifications(deviceId)
                } catch (e: Exception) {
                    log("✗ Fin de synchronisation : ${e.describe()}")
                }
            }
        }

        log(if (allOk) "Synchronisation terminée." else "Synchronisation terminée avec des erreurs.")
        return allOk
    }

    private fun Exception.describe(): String = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    companion object {
        /** Jours les plus récents jamais effacés du bracelet : aujourd'hui et hier. */
        const val KEEP_RECENT_DAYS = 2L
    }
}
