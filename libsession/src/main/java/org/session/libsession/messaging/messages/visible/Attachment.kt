package org.session.libsession.messaging.messages.visible

import android.util.Size
import android.webkit.MimeTypeMap
import com.google.protobuf.ByteString
import org.session.libsession.messaging.sending_receiving.attachments.Attachment as SignalAttachment
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.messages.SignalServiceAttachmentPointer
import org.session.libsignal.protos.SignalServiceProtos
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class Attachment {
    var filename: String? = null
    var contentType: String? = null
    var key: ByteArray? = null
    var digest: ByteArray? = null
    var kind: Kind? = null
    var caption: String? = null
    var size: Size? = null
    var sizeInBytes: Int? = 0
    var url: String? = null

    companion object {

        // This method is ONLY ever used when an older Session Android client sends a file to a modern
        // Session Android client and as such the file has a null filename and we need to try our best
        // to synthesise a reasonable filename.
        //
        // Note: We CANNOT use the MediaUtils class for this because it's in the thoughtcrime namespace
        // and the dependency graph between libsession and thoughtcrime does not go that way - and should
        // we move MediaUtils into _this_ namespace then we will have to drag IT'S dependencies and so on.
        private fun generateFilenameFromReceivedTypeForLegacyClients(mimeType: String): String {
            // Prepare a date formatter and generate a formatted date from the current time, e.g. 2025-01-17-154207
            val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault())
            val fileReceivedDate = dateFormatter.format(System.currentTimeMillis())

            // Normalise the mime type & determine the file extension based on the MIME type
            val lowerMime = mimeType.lowercase()
            val extension = when (lowerMime) {
                // IMAGES
                "image/jpeg", "image/pjpeg" -> ".jpg"
                "image/png" -> ".png"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                "image/bmp", "image/x-ms-bmp" -> ".bmp"
                "image/tiff" -> ".tiff"
                "image/x-icon", "image/vnd.microsoft.icon" -> ".ico"
                "image/svg+xml" -> ".svg"
                "image/heif", "image/heic" -> ".heic"

                // AUDIO
                "audio/mpeg" -> ".mp3"
                "audio/mp4" -> ".m4a"
                "audio/ogg" -> ".ogg"
                "audio/wav", "audio/x-wav" -> ".wav"
                "audio/aac", "audio/x-aac" -> ".aac"
                "audio/midi", "audio/x-midi" -> ".midi"
                "audio/flac" -> ".flac"
                "audio/x-wavpack" -> ".wv"
                "audio/x-ms-wma" -> ".wma"

                // VIDEO
                "video/mp4" -> ".mp4"
                "video/3gpp" -> ".3gp"
                "video/quicktime" -> ".mov"
                "video/x-matroska" -> ".mkv"
                "video/webm" -> ".webm"
                "video/x-msvideo" -> ".avi"
                "video/x-ms-wmv" -> ".wmv"
                "video/mpeg" -> ".mpeg"
                "video/x-flv" -> ".flv"

                // DOCUMENTS
                "application/pdf" -> ".pdf"
                "text/plain" -> ".txt"
                "application/rtf" -> ".rtf"
                "application/postscript" -> ".ps"
                "application/vnd.oasis.opendocument.text" -> ".odt"
                "application/vnd.oasis.opendocument.spreadsheet" -> ".ods"
                "application/vnd.oasis.opendocument.presentation" -> ".odp"
                "application/vnd.oasis.opendocument.graphics" -> ".odg"

                // OFFICE DOCS
                "application/msword" -> ".doc"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
                "application/vnd.ms-excel" -> ".xls"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
                "application/vnd.ms-powerpoint" -> ".ppt"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"

                // ARCHIVES
                "application/zip", "application/x-zip-compressed" -> ".zip"
                "application/x-7z-compressed" -> ".7z"
                "application/x-rar-compressed", "application/vnd.rar" -> ".rar"
                "application/x-tar" -> ".tar"
                "application/gzip", "application/x-gzip" -> ".gz"
                "application/x-bzip2" -> ".bz2"
                "application/x-xz" -> ".xz"
                "application/x-iso9660-image" -> ".iso"
                "application/vnd.android.package-archive" -> ".apk"
                "application/x-lzma" -> ".lzma"
                "application/x-tar+gzip" -> ".tar.gz"
                "application/x-tar+bzip2" -> ".tar.bz2"
                "application/x-tar+xz" -> ".tar.xz"
                "application/x-cpio" -> ".cpio"

                // PROGRAMMING FILES
                "application/javascript" -> ".js"
                "application/json" -> ".json"
                "application/xml" -> ".xml"
                "application/x-yaml", "text/yaml" -> ".yaml"
                "text/html" -> ".html"
                "text/css" -> ".css"
                "text/x-python" -> ".py"
                "text/x-java-source" -> ".java"
                "text/x-c" -> ".c"
                "text/x-c++src" -> ".cpp"
                "text/x-csharp" -> ".cs"
                "application/x-kotlin" -> ".kt"
                "text/x-php" -> ".php"
                "application/x-ruby", "text/x-ruby" -> ".rb"
                "application/x-perl" -> ".pl"
                "application/x-shellscript" -> ".sh"
                "application/x-sql", "text/x-sql" -> ".sql"
                "text/markdown" -> ".md"
                "application/x-latex" -> ".tex"
                "application/x-tcl" -> ".tcl"
                "application/x-r" -> ".r"
                "application/x-matlab" -> ".m"
                "application/x-go" -> ".go"
                "application/x-rust" -> ".rs"
                "application/x-swift" -> ".swift"
                "application/x-dart" -> ".dart"
                "application/x-scala" -> ".scala"
                "text/x-vbscript" -> ".vbs"
                "application/x-assembly" -> ".asm"

                // E-BOOKS
                "application/epub+zip" -> ".epub"
                "application/x-mobipocket-ebook" -> ".mobi"
                "application/vnd.amazon.ebook" -> ".azw"

                // CSV / TSV
                "text/csv" -> ".csv"
                "text/tab-separated-values" -> ".tsv"

                // STREAMING PLAYLISTS
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/x-mpegurl" -> ".m3u8"

                // LINUX PACKAGES
                "application/x-debian-package" -> ".deb"
                "application/x-rpm" -> ".rpm"

                // MISC
                "application/x-shockwave-flash" -> ".swf"
                "application/x-msdownload" -> ".exe"
                "application/java-archive" -> ".jar"

                // Default fallback
                else -> ".bin" // Generic extension if unknown
            }

            // Optional category logic for readability in the filename
            val category = when {
                lowerMime.startsWith("image/") -> "Image"
                lowerMime.startsWith("audio/") -> "Audio"
                lowerMime.startsWith("video/") -> "Video"
                lowerMime == "application/pdf" -> "PDF"
                lowerMime.startsWith("application/vnd.ms-powerpoint") || lowerMime.startsWith("application/vnd.openxmlformats-officedocument.presentationml") ->
                    "Presentation"
                lowerMime.startsWith("application/vnd.ms-excel") || lowerMime.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") ->
                    "Spreadsheet"
                lowerMime.startsWith("application/msword") || lowerMime.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") ->
                    "Document"
                lowerMime.startsWith("application/") -> "File"
                lowerMime.startsWith("text/") -> "Text"
                else -> "File" // Generic catch-all
            }

            // Example filename: Session-Image-2025-01-17-154207.jpg
            return "Session-$category-$fileReceivedDate$extension"
        }


        fun fromProto(proto: SignalServiceProtos.AttachmentPointer): Attachment {
            val result = Attachment()

            // Note: For legacy Session Android clients this filename will be null and we'll synthesise an appropriate filename
            // once we have the content / mime type (below).
            result.filename = proto.fileName

            fun inferContentType(): String {
                val fileName = result.filename
                val fileExtension = File(fileName).extension
                val mimeTypeMap = MimeTypeMap.getSingleton()
                return mimeTypeMap.getMimeTypeFromExtension(fileExtension) ?: "application/octet-stream"
            }
            result.contentType = proto.contentType ?: inferContentType()

            // If we were given a null filename from a legacy client but we at least have a content type (i.e., mime type)
            // then the best we can do is synthesise a filename based on the content type and when we received the file.
            if (result.filename.isNullOrEmpty() && !result.contentType.isNullOrEmpty()) {
                result.filename = generateFilenameFromReceivedTypeForLegacyClients(result.contentType!!)
            }

            result.key = proto.key.toByteArray()
            result.digest = proto.digest.toByteArray()
            val kind: Kind = if (proto.hasFlags() && proto.flags.and(SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) > 0) {
                Kind.VOICE_MESSAGE
            } else {
                Kind.GENERIC
            }
            result.kind = kind
            result.caption = if (proto.hasCaption()) proto.caption else null
            val size: Size = if (proto.hasWidth() && proto.width > 0 && proto.hasHeight() && proto.height > 0) {
                Size(proto.width, proto.height)
            } else {
                Size(0,0)
            }
            result.size = size
            result.sizeInBytes = if (proto.size > 0) proto.size else null
            result.url = proto.url

            return result
        }

        fun createAttachmentPointer(attachment: SignalServiceAttachmentPointer): SignalServiceProtos.AttachmentPointer? {
            val builder = SignalServiceProtos.AttachmentPointer.newBuilder()
                    .setContentType(attachment.contentType)
                    .setId(attachment.id)
                    .setKey(ByteString.copyFrom(attachment.key))
                    .setDigest(ByteString.copyFrom(attachment.digest.get()))
                    .setSize(attachment.size.get())
                    .setUrl(attachment.url)

            // Filenames are now mandatory
            builder.fileName = attachment.filename

            if (attachment.preview.isPresent) { builder.thumbnail = ByteString.copyFrom(attachment.preview.get())               }
            if (attachment.width > 0)         { builder.width = attachment.width                                                }
            if (attachment.height > 0)        { builder.height = attachment.height                                              }
            if (attachment.voiceNote)         { builder.flags = SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE }
            if (attachment.caption.isPresent) { builder.caption = attachment.caption.get()                                      }

            return builder.build()
        }
    }

    enum class Kind {
        VOICE_MESSAGE,
        GENERIC
    }

    fun isValid(): Boolean {
        // key and digest can be nil for open group attachments
        return (contentType != null && kind != null && size != null && sizeInBytes != null && url != null)
    }

    fun toProto(): SignalServiceProtos.AttachmentPointer? {
        TODO("Not implemented")
    }

    fun toSignalAttachment(): SignalAttachment? {
        if (!isValid()) return null
        return PointerAttachment.forAttachment((this))
    }

    fun toSignalPointer(): SignalServiceAttachmentPointer? {
        if (!isValid()) return null
        return SignalServiceAttachmentPointer(0, contentType, key, Optional.fromNullable(sizeInBytes), null,
                size?.width ?: 0, size?.height ?: 0, Optional.fromNullable(digest), filename,
                kind == Kind.VOICE_MESSAGE, Optional.fromNullable(caption), url)
    }

}