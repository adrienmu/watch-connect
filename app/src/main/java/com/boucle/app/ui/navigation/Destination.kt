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
    /** Détail d'une séance (carte du tracé GPS, allure, FC, altitude). */
    ActivityDetail(false),
}
