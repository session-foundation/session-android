package org.thoughtcrime.securesms.util

import android.content.Context
import androidx.collection.IntObjectMap
import androidx.collection.LruCache
import androidx.collection.buildIntObjectMap
import com.opencsv.CSVReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import java.io.DataInputStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlin.math.absoluteValue


@OptIn(ExperimentalUnsignedTypes::class)
@Singleton
class IP2Country @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    // Cache for IP to country name lookups.
    // Note that due to the limitation of LruCache, the country record can't be null.
    private val countryCache = LruCache<String, Optional<CountryRecord>>(16)

    private var ipv4ToCountryRef = WeakReference<Pair<UIntArray, IntArray>>(null)

    /**
     * Loads and caches the IPv4 to country code mapping from the raw resource file. The data is stored as two parallel arrays:
     * - `UIntArray` for the starting IP addresses of the blocks (in integer form).
     * - `IntArray` for the corresponding country codes.
     */
    private fun ensureIpv4ToCountry(): Pair<UIntArray, IntArray> {
        ipv4ToCountryRef.get()?.let { return it }

        synchronized(this) {
            // Double-checked locking to avoid unnecessary synchronization after the initial load.
            ipv4ToCountryRef.get()?.let { return it }

            val start = System.currentTimeMillis()

            val computed = context.resources.openRawResource(R.raw.geolite2_country_blocks_ipv4)
                .let(::DataInputStream)
                .use {
                    val size = it.available() / 8

                    val ips = UIntArray(size)
                    val codes = IntArray(size)
                    var i = 0

                    while (it.available() > 0) {
                        ips[i] = it.readInt().toUInt()
                        codes[i] = it.readInt()
                        i++
                    }

                    ips to codes
                }

            Log.d(TAG, "Loaded IPv4 to country mapping in ${System.currentTimeMillis() - start}ms")

            ipv4ToCountryRef = WeakReference(computed)
            return computed
        }
    }

    private data class CountryRecord(
        val countryCode: String,
        val englishName: String,
    )

    private val countryByRegionId: IntObjectMap<CountryRecord> by lazy {
        CSVReader(context.resources.openRawResource(R.raw.geolite2_country_locations_english).reader())
            .use { csv ->
                csv.skip(1)

                val start = System.currentTimeMillis()
                buildIntObjectMap {
                    csv.asSequence()
                        .filter { cols -> !cols[0].isNullOrEmpty() && !cols[1].isNullOrEmpty() }
                        .forEach { cols ->
                            val code = cols[0].toInt()
                            val name = cols[5]
                            put(code, CountryRecord(
                                countryCode = cols[4],
                                englishName = name,
                            ))
                        }
                }.apply {
                    Log.d(TAG, "Loaded country code to name mapping in ${System.currentTimeMillis() - start}ms")
                }
            }
    }

    private fun ipv4Int(ip: String): UInt =
        ip.splitToSequence(".", "/", ",").take(4).fold(0U) { acc, s -> acc shl 8 or s.toUInt() }


    // region Implementation
    suspend fun lookupCountry(ip: String, locale: Locale = Locale.getDefault()): String? {
        // return early if cached
        var found = countryCache[ip]
        if (found == null) {
            found = Optional.ofNullable(withContext(Dispatchers.Default) {
                val ipInt = ipv4Int(ip)
                val (ips, codes) = ensureIpv4ToCountry()

                val index = ips.binarySearch(ipInt).let { it.takeIf { it >= 0 } ?: (it.absoluteValue - 2) }
                val code = codes.getOrNull(index)
                code?.let(countryByRegionId::get)
            })

            countryCache.put(ip, found)

            if (found.isEmpty) {
                Log.d(TAG, "Country name for $ip couldn't be found")
            }
        }

        return found.getOrNull()?.let { record ->
            Locale.Builder()
                .setRegion(record.countryCode)
                .build()
                .displayCountry
                .takeIf { it.isNotBlank() }
                ?: record.englishName

        }
    }
    // endregion

    companion object {
        private const val TAG = "IP2Country"
    }
}
