# Build the APK

This machine has Android Studio installed. To build the APK:

1. Open **Android Studio** from your Start menu or `C:\Program Files\Android\Android Studio\bin\studio64.exe`.
2. If this is your first time opening Android Studio, complete the **Setup Wizard** (Standard setup). This will automatically download and install the required Android SDK components to `C:\Users\Niket\AppData\Local\Android\Sdk`.
3. In Android Studio, click **Open** and select this project folder:

   `C:\Users\Niket\Documents\Github Repo\NeuroVision`

4. Wait for Gradle sync to finish indexing dependencies.
5. To create the installable APK, click in the top menu:

   **Build** → **Build App Bundle(s) / APK(s)** → **Build APK(s)**

6. Once the build finishes, Android Studio will show a popup with a **locate** link. The APK will be generated at:

   `app\build\outputs\apk\debug\app-debug.apk`

7. Connect your phone with USB debugging enabled, or transfer `app-debug.apk` directly to your phone to install and test NeuroVision.

---

## Command Line Build (Alternative)

Once the Android SDK is downloaded by Android Studio, you can also build directly from PowerShell / Terminal by running:

```powershell
.\gradlew.bat assembleDebug
```
