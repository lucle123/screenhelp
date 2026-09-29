# Screen Help AI - Gemini MVP

Android MVP for a floating AI homework helper.

## Flow

1. Open the app and enter a Gemini API key (**SAVE API KEY**).
2. Grant **Appear on top** (overlay) and **Notifications**.
3. Tap **START SCREEN HELP**. Android asks once for screen-capture permission (entire screen).
4. The **HELP** bubble appears over other apps. Drag it anywhere.
5. Tap HELP. The bubble and any old answer are hidden, a clean frame is captured, and the bubble comes straight back.
6. Gemini analyzes the screenshot and the answer streams into a floating card (and a notification).
7. Close the card with the X button. Hold the bubble for 4 seconds until it shows X, then tap it to turn Screen Help off (or use the notification's **Turn off** action).

## AI

The app uses the Gemini Developer API with `gemini-3.8-flash` and sends the screenshot as inline base64 image data.

## Important

- The API key is stored in the app's private SharedPreferences.
- This is a personal/testing MVP. Do not ship a shared developer API key inside a public APK.
- Android requires the user's explicit MediaProjection confirmation before screen capture. The session stays alive while Screen Help is on (Android shows its screen-sharing indicator), because on Android 14+ the permission is single-use.
- The app does not read the screen continuously; the capture display is paused and a frame is taken only after HELP is pressed.
