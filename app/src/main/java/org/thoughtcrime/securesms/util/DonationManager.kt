package org.thoughtcrime.securesms.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Companion.SEEN_1
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Companion.TRUE
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
        val hasDonated = getHasDonated() || getHasCopiedLink()
        val seenAmount = getSeenCTAAmount()

        // return early if the user has already donated/copied the donation url
        // or if they have reached the max views
        if(hasDonated || seenAmount >= maxDonationCTAViews)
            return false

        // if we gave a positive review and never donated, then show the donate CTA
        if(getShowFromReview()) {
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

    private fun getHasDonated(): Boolean{
        val debug = prefs.hasDonatedDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else prefs.hasDonated()
    }

    private fun getHasCopiedLink(): Boolean{
        val debug = prefs.hasCopiedDonationURLDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else prefs.hasCopiedDonationURL()
    }

    private fun getSeenCTAAmount(): Int{
        val debug = prefs.seenDonationCTAAmountDebug()
        return if(debug != null){
            debug.toInt()
        } else prefs.seenDonationCTAAmount()
    }

    private fun getShowFromReview(): Boolean{
        val debug = prefs.showDonationCTAFromPositiveReviewDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else prefs.showDonationCTAFromPositiveReview()
    }
}