# Screen Help AI - Gemini MVP

Android MVP for a floating AI homework helper.

## Flow

1. Open the app.
2. Enter a Gemini API key and tap **Lưu key + Bật Help**.
3. Grant the overlay permission.
4. Use the **HELP** bubble over another app.
5. Tap HELP.
6. The bubble is hidden before screen capture.
7. Android asks for screen-capture permission when needed.
8. A clean screenshot is captured without the Screen Help bubble.
9. The bubble is restored immediately after capture.
10. Gemini analyzes the screenshot and the result appears in a notification and floating result card.

## AI

The app uses the Gemini Developer API with `gemini-2.5-flash` and sends the screenshot as inline base64 image data.

## Important

- The API key is stored in the app's private SharedPreferences.
- This is a personal/testing MVP. Do not ship a shared developer API key inside a public APK.
- Android still requires the user's explicit MediaProjection confirmation before screen capture.
- The app does not continuously capture the screen; it captures only after HELP is pressed and permission is granted.
