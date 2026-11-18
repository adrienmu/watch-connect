package com.boucle.app.ui.navigation

/** Écrans de l'app. Les quatre premiers sont les onglets de la barre de navigation. */
enum class Destination(val isTab: Boolean) {
    Today(true),
    Sleep(true),
    Activity(true),
    Devices(true),
    Recovery(false),
    Energy(false),
    Heart(false),
    Vo2Max(false),
    Body(false),
    Pairing(false),
    Profile(false),
    /** Réglage des objectifs quotidiens (pas, calories actives). */
    Goals(false),
    /** Détail d'une séance (carte du tracé GPS, allure, FC, altitude). */
    ActivityDetail(false),
    /** Nouvelle activité : choix du sport et état des capteurs avant le départ. */
    ActivityStart(false),
    /** Explication avant la demande d'accès à la position. */
    ActivityPermission(false),
    /** Activité en cours d'enregistrement. */
    ActivityRecording(false),
    /** Résumé de fin de séance, avant l'enregistrement. */
    ActivitySummary(false),
    /** Activité détectée par le bracelet, à confirmer. */
    ActivityDetected(false),
    /** Modification d'une séance (nom, sport, terrain, rognage, notes, suppression). */
    ActivityEdit(false),
}
