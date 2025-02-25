![Añadir un título (3)](https://github.com/user-attachments/assets/0bb8b4b6-ec23-478d-b3eb-54c9454e12a5)

***NOTICE: EVERY PERSON WHO WANTS TO COLLABORATE ON THE PROJECT IS WELCOME, WE NEED SOMEONE WHO IS AN EXPERT IN UI TO FIX A SMALL KEYBOARD BUG***
*** TO COLLABORATE, WRITE AN ISSUE ***



# ThreadScoopOnionChat

Esta version es un Fork de " https://github.com/session-foundation/session-android ", enfocado en la privacidad.

## How Did ThreadScoopOnionChat Come About?

I have always been a fan of privacy and security in communications, and I am constantly researching potential security breaches in messaging applications.
ThreadScoopOnionChat emerged after the analysis of this blog "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/", in which several aspects are described as to why using Session is not recommended, and I wondered how it is possible that a foundation focused on privacy—with the grants and support it receives—has not been able to improve this protocol.
I still do not understand the answer, since my resources are much smaller than those of a foundation compared to that of a person who is simply a fan of privacy and security.
In this blog, certain problems were described:

1> "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/#insufficient-entropy-ed25519"
2> "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/#in-band-negotiation"
3> "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/#public-keys-aes-gcm"

## Solutions Provided in This Version

## Regarding reference 1> "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/#insufficient-entropy-ed25519"
This flaw is fixed by using 256-bit keys instead of 128-bit keys.

Corrected files:

<p> <b> KeyPairUtilities.kt </b></p> <p> <b> MnemonicCodec.kt </b></p> <p> <b> KeyPairUtilities.kt </b></p>
The length of the generated seed is increased to 32 bytes:
val seed = sodium.randomBytesBuf(32)
The 16-byte zero padding is removed and the seed is passed directly to cryptoSignSeedKeypair(seed)

<p> <b> MnemonicCodec.kt </b></p>
·In the decode method, it is enforced that the mnemonic phrase has exactly 25 words:
if (words.size != 25) throw DecodingError.InputTooShort

With these adjustments, the issue of insufficient entropy (now 256 bits) is corrected and recovery with 25 words for the Ed25519 key is implemented.

![image](https://github.com/user-attachments/assets/217b5a7c-a455-4938-88af-961cf660cad9)


## Regarding reference 2> "https://soatok.blog/2025/01/14/dont-use-session-signal-fork/#in-band-negotiation"

To solve this issue, "ExtraSecurity" is implemented, an activity accessible from the settings.
![image](https://github.com/user-attachments/assets/5e54af7c-e6a4-4564-94ad-63a178a9d973)
 ![image](https://github.com/user-attachments/assets/f23be3db-1c84-417e-8743-fe171ccf1c99) 
 ![image](https://github.com/user-attachments/assets/3bec5806-1bf2-40e8-ae98-f17a10fcbbec)
![image](https://github.com/user-attachments/assets/c45b7416-2849-47c4-9641-bbca2eecf3af)

![image](https://github.com/user-attachments/assets/a1b174a1-5cb8-4c24-a35a-2a89d6af2597)
![image](https://github.com/user-attachments/assets/062e14d3-a614-4d8a-a4a0-16978d590da5)





  ![image](https://github.com/user-attachments/assets/07586efc-9087-4f2c-84de-41562d3cfaca)

![image](https://github.com/user-attachments/assets/4336130e-7f01-4e5e-90a0-e09d95ed139c) ![image](https://github.com/user-attachments/assets/c6f76289-404e-4452-8d8b-0758462ea8f6)



## Important Details:
<p> <b> The addition of this extra layer with out-of-band symmetric encryption can solve the problem. By doing so, the message is protected even before entering the session protocol, so that an attacker cannot inject or modify the included Ed25519 key, since only the sender and the receiver know the key for that first layer of encryption.
In other words, by first applying encryption with a pre-shared secret key, it prevents an adversary from altering the content or substituting the public key without breaking that protection. This effectively links the Ed25519 signature to the original message, turning the signature from a mere integrity check (similar to a CRC32) into a true guarantee of authenticity.

However, it is essential to ensure that:

The shared key is exchanged and stored securely out-of-band, for example, exchanged personally, or through another channel that is not Session. </b></p>

<p> <b>Preliminary Symmetric Encryption:</b></p>
The message is encrypted using a shared key (pre-shared between sender and receiver) before the session protocol encryption is applied. This ensures that the content, including the Ed25519 key, is protected against tampering.

<p> <b>Protection of the Public Key:</b></p>
By incorporating the additional encryption layer, the Ed25519 public key is no longer transmitted in clear text. This mitigates the vulnerability where an attacker could arbitrarily substitute that key, effectively reducing the signature to a verification similar to a CRC32.

<p> <b>Secure Key Exchange:</b></p>
The symmetric key is exchanged and stored securely out-of-band (for example, through export in Settings/Extrasecurity), ensuring that only the sender and receiver have access to it.

<p> <b>Security Benefit:</b></p>
With this change, the authenticity of the message is strengthened, as the Ed25519 signature is securely linked to the original message, eliminating the possibility of injection or malicious modification of the public key.

This approach reinforces the overall security of the protocol, preserving the integrity and authenticity of the message throughout the encryption and transmission process.

To implement this function, several files were modified:
<p> <b>·Storage.kt </b></p> <p> <b>·SQLCipherOpenHelper.kt </b></p> <p> <b>·Messagesender.kt </b></p> <p> <b>·Messagereceiver.kt </b></p> <p> <b>·Threadatabase.java </b></p> <p> <b>·ConversationsV2.kt </b></p> <p> <b>·SettingsActivity</b></p> <p> <b>·Extrasecurityactivity.kt </b></p> <p> <b>·Storageprotocol.kt </b></p> <p> <b>·ConversationMenuHelper.kt </b></p> <p> <b>·MessagingModuleConfiguration.kt </b></p> <p> <b>·KeyPairUtilities </b></p> <p> <b>·Contact.kt </b></p> <p> <b>·SignalServiceProto </b></p> <p> <b>·MessageWrapper.kt </b></p> <p> <b>·ReceivedMessageHandler.kt: </b></p>



## STEPS TO CONFIGURE AND USE EXTRASECURITY (THREADSOOP ENCRYPTION)

 
1. Access the Settings:
   - Open the application and navigate to "Settings".
   - Within "Settings", select the "Extrasecurity" option.

2. Key Generation:
   - In the "Extrasecurity" section, generate the keys that will be used for encryption.
   - Available options:
     a. "Generate Random" to create a random key.
     b. "Import from QR" to import an existing key via QR code.
   - It is recommended to have a unique key for each chat.

3. Key Export:
   - To export a key, long-press the desired key in the list.
   - Select the "Export as QR" option to generate a QR code for the key.

4. Key Import:
   - Use the "Import key from QR" option to scan the QR code and add the key.

5. Importance of Alias:
   - It is essential that both the sender and the receiver use the same key and the same alias.
   - Example: If a key is exported with the alias "Test", when importing the key, it must be assigned exactly "Test".

6. Activation of Encryption in the Conversation:
   - Start a normal chat with the desired contact.
   - In the conversation's drop-down menu, select "ExtraSecurity".
   - Choose the encryption key that has the same alias on both devices.
   - Activate the extra encryption and save the configuration.

7. Result:
   - The conversations will be protected by ThreadScoop encryption, ensuring that only the participants with the correct key and alias can decrypt the messages

<p align="center">
  <a href="https://example.com/donate" target="_blank">
    <img src="https://img.shields.io/badge/Donate-USDT-green?style=for-the-badge&logo=tether" alt="Donate USDT">
  </a>
  <br>
  <small>If you want, donate or leave a compliment. My USDT Wallet: <code>TNufycDSRfWbQs79e2Fjyy2DKrNiySN95m</code></small>
</p>
