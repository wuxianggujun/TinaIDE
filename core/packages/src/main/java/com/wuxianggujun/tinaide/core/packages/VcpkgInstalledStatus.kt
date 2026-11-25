package com.wuxianggujun.tinaide.core.packages

import java.io.File

/** One parser for private vcpkg status records and exported package indexes. */
internal object VcpkgInstalledStatus {
    fun read(file: File, triplet: String, checkActive: () -> Unit): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val bytes = PackageMetadataFiles.readBytes(file, VcpkgRepository.MAX_STATUS_BYTES, checkActive)
        val installed = sortedMapOf<String, String>()
        val fields = mutableMapOf<String, String>()
        fun finish() {
            val name = fields["Package"]
            if (fields["Architecture"] == triplet && fields["Status"] == "install ok installed" &&
                "Feature" !in fields && name != null && VcpkgRepository.PORT_NAME.matches(name)) {
                installed[name] = fields["Version"].orEmpty()
            }
            fields.clear()
        }
        for (line in PackageMetadataFiles.decodeUtf8(bytes).lineSequence()) {
            checkActive()
            if (line.isBlank()) finish()
            else if (!line.first().isWhitespace() && ':' in line) fields[line.substringBefore(':')] = line.substringAfter(':').trim()
        }
        finish()
        return installed
    }

    fun write(file: File, triplet: String, packages: Map<String, String>, checkActive: () -> Unit = {}) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            var totalBytes = 0L
            packages.toSortedMap().forEach { (name, version) ->
                checkActive()
                require(VcpkgRepository.PORT_NAME.matches(name) && version.none { it == '\r' || it == '\n' || it == '\u0000' })
                val record = "Package: $name\nArchitecture: $triplet\nVersion: $version\nStatus: install ok installed\n\n"
                totalBytes += record.toByteArray(Charsets.UTF_8).size
                require(totalBytes <= VcpkgRepository.MAX_STATUS_BYTES)
                writer.write(record)
            }
        }
    }
}
