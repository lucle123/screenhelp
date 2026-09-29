# Screen Help - GitHub APK build

This is the Android project at repository root.

1. Upload all files/folders in this directory to the root of a GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build Screen Help APK**.
4. Click **Run workflow**.
5. After it finishes, download the **ScreenHelp-debug** artifact.
6. Extract it to get `app-debug.apk`.

The workflow installs Gradle in GitHub Actions, so `gradlew` is not required in the repository.
