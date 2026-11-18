# Déclarations Health Connect (Play Console)

À coller dans Play Console > Contenu de l'application > Health Connect, un texte par type de données.
Chaque texte suit la grille attendue par l'examen : **fonction précise dans l'app → où elle apparaît → bénéfice direct pour l'utilisateur**.
Version anglaise fournie : la réponse de Google cite les types en anglais, l'examinateur lit plus vite l'anglais.

Contexte commun (à mettre dans la description générale si le formulaire en a une) :

> Boucle is a local-only health dashboard. It reads (never writes) data that the Withings app (Health Mate) stores in Health Connect from the user's Withings smart scale and watch, and displays it next to data from a Polar band. All processing happens on the device; no data leaves the phone, is shared, sold or used for ads.

---

## Weight

> Used on the "Corps" (Body) screen to show the user's latest weigh-in from their smart scale, a 30-day weight chart and the change over 7 and 30 days. Benefit: the user can track their weight trend in the same app as their sleep, recovery and activity data, without typing it in.

## BodyFat

> Used on the "Corps" (Body) screen, "Composition corporelle" card: displays the body fat percentage measured by the user's smart scale at the latest weigh-in, as a value and a fat/lean split bar. Benefit: the user sees how their body composition evolves, not only their weight.

## LeanBodyMass

> Used on the "Corps" (Body) screen, "Composition corporelle" card: displays the lean body mass (kg) measured by the smart scale, next to the body fat bar. Benefit: the user can check that weight changes come from fat rather than muscle.

## BoneMass

> Used on the "Corps" (Body) screen, "Composition corporelle" card: displays the bone mass (kg) measured by the smart scale at the latest weigh-in. Benefit: the user sees the full composition breakdown reported by their scale in one place.

## BodyWaterMass

> Used on the "Corps" (Body) screen, "Composition corporelle" card, shown as "Masse hydrique" (body water, kg) measured by the smart scale. Benefit: the user can follow their hydration level alongside weight and composition.

## OxygenSaturation

> Used on the "Corps" (Body) screen, "SpO2 · nuit" card: displays the average and minimum blood oxygen saturation measured overnight by the user's watch. Benefit: the user can check their nightly SpO2 next to their sleep score and spot unusually low nights.

## Steps (StepsCadence n'est pas demandé : seulement READ_STEPS)

> Read only from the Withings app, for users whose watch replaces the Polar band. Used on the "Aujourd'hui" (Today) and "Activité" (Activity) screens to show today's step count and a 7-day step chart, and as an input of the on-device "Énergie" score (energy spent through the day). Benefit: the user tracks daily activity and gets an accurate energy score without wearing the band.

## Distance

> Read only from the Withings app, for users whose watch replaces the Polar band. Used on the "Activité" (Activity) screen to show the distance covered each day and the distance of each workout session (also on the workout detail screen). Benefit: the user knows how far they walked or ran each day and per workout.
