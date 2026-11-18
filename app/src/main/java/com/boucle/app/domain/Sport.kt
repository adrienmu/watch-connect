package com.boucle.app.domain

/*
 * Sports que l'app sait lancer, reconnaître et corriger : marche, course à pied (un seul profil pour la route, le
 * trail et l'ultra-trail) et vélo. Pur Kotlin, testable sur la JVM.
 */

/** Sport d'une séance. [sportId] est l'identifiant Polar écrit dans la séance quand l'app la crée elle-même. */
enum class Sport(val sportId: Long, val label: String) {
    WALK(3L, "Marche"),
    RUN(1L, "Course"),
    BIKE(2L, "Vélo");

    companion object {
        // Identifiants de la liste de sports Polar : course (1), jogging (4), trail (27), ultra (55) ;
        // vélo (2), VTT (5), vélo de route (38) ; marche (3), randonnée (11).
        private val RUN_IDS = setOf(1L, 4L, 27L, 55L)
        private val BIKE_IDS = setOf(2L, 5L, 38L)
        private val WALK_IDS = setOf(3L, 11L)

        fun named(name: String?): Sport? = entries.firstOrNull { it.name == name }

        /** Sport d'après l'identifiant Polar, sinon d'après le nom de la séance (Health Connect) ; `null` pour un autre sport. */
        fun of(sportId: Long?, name: String?): Sport? {
            when (sportId) {
                in RUN_IDS -> return RUN
                in BIKE_IDS -> return BIKE
                in WALK_IDS -> return WALK
            }
            val text = name.orEmpty().lowercase()
            return when {
                "course" in text || "run" in text || "trail" in text -> RUN
                "vélo" in text || "velo" in text || "cycl" in text -> BIKE
                "marche" in text || "rando" in text || "walk" in text -> WALK
                else -> null
            }
        }
    }
}

/** Terrain d'une course : une étiquette, pas un autre sport. */
enum class Terrain(val label: String) {
    ROAD("Route"),
    TRAIL("Trail"),
    ULTRA("Ultra-trail");

    companion object {
        fun named(name: String?): Terrain? = entries.firstOrNull { it.name == name }
    }
}

/** Ressenti noté à la fin d'une séance, du plus facile au plus dur ; stocké par son indice. */
object Feel {
    val LABELS = listOf("Facile", "Modéré", "Soutenu", "Dur", "À fond")
}

/** Zones cardiaques affichées pendant l'effort : nom de chacune des 5 zones. */
object HrZones {
    val NAMES = listOf("Très facile", "Facile", "Soutenu", "Dur", "Maximal")

    /** FC max qui sert aux zones : celle du profil, sinon 220 − âge, sinon une valeur courante. */
    fun maxHr(profileMaxHr: Int?, age: Int?): Int = profileMaxHr?.takeIf { it > 0 } ?: age?.let { 220 - it } ?: DEFAULT_MAX_HR

    /** Bas de la zone 1 (50 % de la FC max) : début de l'échelle affichée. */
    fun floor(maxHr: Int): Int = (maxHr * 0.5).toInt()

    const val DEFAULT_MAX_HR = 190
}

/** Dépense estimée d'une séance enregistrée par le téléphone, faute de mesure par le bracelet. */
object CalorieEstimate {
    private const val DEFAULT_WEIGHT_KG = 70.0

    /**
     * Course et marche : coût par kilo et par kilomètre (environ 1 kcal en courant, 0,75 en marchant). Vélo : MET
     * selon la vitesse moyenne, sur la durée.
     */
    fun kcal(sport: Sport, distanceMeters: Double, durationSec: Double, weightKg: Float?): Int {
        val weight = weightKg?.takeIf { it > 0f }?.toDouble() ?: DEFAULT_WEIGHT_KG
        val km = distanceMeters / 1000
        val value = when (sport) {
            Sport.RUN -> 1.0 * weight * km
            Sport.WALK -> 0.75 * weight * km
            Sport.BIKE -> {
                val hours = durationSec / 3600
                val speed = if (hours > 0) km / hours else 0.0
                val met = when {
                    speed < 16 -> 4.0
                    speed < 19 -> 6.0
                    speed < 22 -> 8.0
                    speed < 25 -> 10.0
                    else -> 12.0
                }
                met * weight * hours
            }
        }
        return value.toInt()
    }
}
