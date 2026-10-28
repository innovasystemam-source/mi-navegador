# Mi Navegador (Android)

Navegador que corre 100% en tu celular. Trae: pestañas, barra de direcciones con búsqueda, modo escritorio, favoritos, descargas y compartir enlace.

## Cómo conseguir tu APK (sin instalar nada en tu computador)

El APK lo compila GitHub gratis. Necesitas una cuenta de GitHub y un computador (desde el celular es muy incómodo).

1. Crea una cuenta gratis en https://github.com
2. Arriba a la derecha toca **+** y luego **New repository**. Ponle de nombre `mi-navegador`, déjalo en **Public** y toca **Create repository**.
3. Descomprime el zip en tu computador. Entra a la carpeta `navegador-local`.
4. En tu repositorio toca **uploading an existing file**. Arrastra **todo el contenido** de la carpeta (incluida la carpeta `.github`, que puede estar oculta) y toca **Commit changes**.
   - Si la carpeta `.github` no aparece o no se sube: toca **Add file > Create new file**, escribe como nombre `.github/workflows/build-apk.yml`, pega el contenido del archivo `build-apk.yml` de este proyecto y toca **Commit changes**.
5. Ve a la pestaña **Actions**. Verás un proceso llamado **Compilar APK** corriendo (tarda unos 5 a 10 minutos). Si no aparece, tócalo en la lista izquierda y usa **Run workflow**.
6. Cuando termine con una palomita verde, entra al proceso y abajo, en **Artifacts**, descarga `mi-navegador-apk`. Es un zip que contiene `app-debug.apk`.
7. Pasa el APK al celular, ábrelo y acepta instalar desde "orígenes desconocidos" si te lo pide.

Si el proceso sale con una X roja, copia el mensaje de error y mándamelo.

## Requisitos

Android 10 o superior.

## Qué no trae todavía

Modo incógnito, historial, bloqueo de anuncios, ni sincronización. El APK es de depuración: sirve para uso personal, no para subir a Play Store.
