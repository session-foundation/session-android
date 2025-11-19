package org.thoughtcrime.securesms.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.utilities.TextSecurePreferences
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DonationManager @Inject constructor(
    @param:ApplicationContext val context: Context,
    val prefs: TextSecurePreferences
){
    companion object {
        const val URL_DONATE = "https://session.foundation/donate#app"
    }

    // increment in days between showing the donation CTA, matching the list index to the number of views of the CTA
    private val donationCTADisplayIncrements = listOf(7, 3, 7, 21)

    private val maxDonationCTAViews = donationCTADisplayIncrements.size

    fun shouldShowDonationCTA(): Boolean{
        val hasDonated = prefs.hasDonated() || prefs.hasCopiedDonationURL()
        val seenAmount = prefs.seenDonationCTAAmount()

        // return early if the user has already donated/copied the donation url
        // or if they have reached the max views
        if(hasDonated || seenAmount >= maxDonationCTAViews)
            return false

        // if we gave a positive review and never donated, then show the donate CTA
        if(prefs.showDonationCTAFromPositiveReview()) {
            prefs.setShowDonationCTAFromPositiveReview(false) // reset flag
            return true
        }

        // display the CTA is the last is later than the increment for the current views
        // the comparison point is either the last time the CTA was seen,
        // or if it was never seen we check the app's install date
        val comparisonDate = if(seenAmount > 0)
            prefs.lastSeenDonationCTA()
        else
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime

        val elapsed = System.currentTimeMillis() - comparisonDate
        val required = TimeUnit.DAYS.toMillis(donationCTADisplayIncrements[seenAmount].toLong())

        return elapsed >= required

    }

    fun onDonationCTAViewed(){
        // increment seen amount
        prefs.setSeenDonationCTAAmount(prefs.seenDonationCTAAmount() + 1)
        // set seen time
        prefs.setLastSeenDonationCTA(System.currentTimeMillis())
    }

    fun onDonationSeen(){
        prefs.setHasDonated(true)
    }

    fun onDonationCopied(){
        prefs.setHasCopiedDonationURL(true)
    }
}