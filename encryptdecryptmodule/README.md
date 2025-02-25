# SecureYourChats :shield: :lock:

[![Ver el Video](https://img.youtube.com/vi/CzAeRdajT2I/0.jpg)](https://www.youtube.com/watch?v=CzAeRdajT2I "Título del Video")


Welcome to SecuerYourChats!
This app allows you to encrypt and decrypt text and files using a variety of algorithms, quickly and easily. It also includes advanced Accessibility features to detect selected or copied text from the clipboard, offering a seamless encryption/decryption flow.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.encrypt.bwt/)

Or download the latest APK from the [Releases Section](https://github.com/R00tedbrain/Encrypt-Decrypt-AndroidAPK/releases/latest).

### Key Features :sparkles:

    Text Encryption/Decryption
        Supported algorithms: AES, DES, CAMELLIA, ChaCha20-Poly1305, XChaCha20-Poly1305, Aegis256
        Intuitive interface to input text, choose an algorithm, and see the result.

    Generate 256 bits key automatic:
    Can generate automatic random keys for encrypt decrypt of 256 bits in Manage Keys

    Share/Import Key encription via  QR
    Share any key created with other people to use the same for encription and import via QR on manage keyes, u can
    Share ur Key pressing on your key and Export, or import  presing import key via qr

    File Encryption/Decryption
        Select files from your device.
        Encrypt or decrypt them using the same algorithms (output file name automatically ends with .encrypted or .decrypted).

    Built-In Key Manager :key:
        Add, delete, or generate random keys.
        Import keys from QR codes, or export them to QR as well.
        Keys are stored securely (EncryptedSharedPreferences).

    Share Encryption Flow
        Share -> Encrypt or Share -> Decrypt text/files from other apps.
        Quick and convenient for multi-app workflows.

    Optional Accessibility Service :handshake:
        Detects selected text (when you highlight text on screen)
        Detects copied text (clipboard)
        Shows a notification prompting you to encrypt/decrypt on the fly, opening the same DialogFlowEncryptionActivity (“Choose Operation”).

    Toggle Accessibility Logic
        A “BurbujaEncryption” button (or similar) can activate/deactivate the logic without disabling it in Android Settings, via an internal flag.
        Perfect for pausing detection without leaving the system settings screen.

    Dialog Flow (DialogFlowEncryptionActivity) :speech_balloon:
        Displays a step-by-step sequence: “Choose Operation” (Encrypt/Decrypt) → “Choose Cipher” → “Pick/Enter Key” → final result.
        Ideal for a friendly, guided experience.

    Android 13+ Support
        Requests POST_NOTIFICATIONS permission if you want to see notifications when text is selected or copied.

        Basic Usage :rocket:

    Main Screen
        Enter text and a key (or pick a saved key).
        Select the algorithm from the spinner.
        Tap Encrypt or Decrypt.
        Copy or share the resulting text.
    File Encryption :file_folder:
        Tap “FILE ENCRYPTION” on the MainActivity.
        Pick a file and an output folder.
        Choose the algorithm and a key.
        Tap EXECUTE.
    Accessibility (optional)
        Enable it in Settings → Accessibility → “Encrypt-DecrypT Service”.
        Once enabled, selecting or copying text in any app will trigger a notification. Tapping it opens the DialogFlow (“Choose Operation”).
        From the app’s MainActivity, use “BurbujaEncryption” to enable or disable the logic without turning the service off in Settings.

    FAQ :question:

    How do I enable/disable the accessibility service logic without going to system settings?
        Tap the “BurbujaEncryption” button in MainActivity. It toggles an internal SharedPreferences flag. If off, the service won’t process events even if it’s enabled in system settings.

    What if I forget my key?
        You won’t be able to decrypt your text/file. Make sure you keep track of your keys or store them in the KeyManager.

    Why am I not seeing notifications when copying text?
        On Android 13+, you must grant notification permission (POST_NOTIFICATIONS).
        Ensure the accessibility service is enabled and the internal logic isn’t toggled off.

### Screenshots

![4](https://github.com/user-attachments/assets/f9c17aa7-15c5-441c-89dc-25555d493f37)
![3](https://github.com/user-attachments/assets/d969396a-b94f-43f1-a563-c9e9c283ea3e)
![2](https://github.com/user-attachments/assets/cc09b8e6-dfe5-4674-9c3a-f6be3b083504)
![1](https://github.com/user-attachments/assets/4d1e47e4-3e80-4545-9bd5-dcfad2b4406c)

### Acknowledgments

:sparkling_heart: Thanks to the open-source community and encryption libraries (BouncyCastle, etc.).

:coffee: Special thanks to all testers for their feedback and reports.

### License📜

Este proyecto está licenciado bajo la [Licencia Pública General de GNU versión 3](https://www.gnu.org/licenses/gpl-3.0.es.html). 
Por favor, revisa el archivo [LICENSE](./LICENSE) para obtener más información.
