# Limpiador

App Android (Kotlin + Compose) para analizar el almacenamiento: espacio libre/ocupado por categoría, detección de duplicados (tamaño → hash de los primeros y últimos 64 KB → SHA-256 completo solo en colisiones) y limpieza de archivos inactivos o residuales. Indexa en Room y refresca el índice en segundo plano con WorkManager.

Cada push a `main` compila el APK en GitHub Actions y lo publica en Releases (`Limpiador.apk`).

Desarrollado por: Gamaliel Huasco.
