package org.thoughtcrime.securesms.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.debugmenu.DebugMenuViewModel.Companion.TRUE
import org.thoughtcrime.securesms.preferences.PreferenceStorage
import org.thoughtcrime.securesms.preferences.SystemPreferences
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DonationManager @Inject constructor(
    @param:ApplicationContext val context: Context,
    val textSecurePreferences: TextSecurePreferences,
    val preferenceStorage: PreferenceStorage
){
    companion object {
        const val URL_DONATE = "https://getsession.org/donate#app"
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
            preferenceStorage[SystemPreferences.SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW] = false // reset flag
            return true
        }

        // display the CTA is the last is later than the increment for the current views
        // the comparison point is either the last time the CTA was seen,
        // or if it was never seen we check the app's install date
        val comparisonDate = if(seenAmount > 0)
            preferenceStorage[SystemPreferences.LAST_SEEN_DONATION_CTA]
        else
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime

        val elapsed = System.currentTimeMillis() - comparisonDate
        val required = TimeUnit.DAYS.toMillis(donationCTADisplayIncrements[seenAmount].toLong())

        return elapsed >= required

    }

    fun onDonationCTAViewed(){
        // increment seen amount
        preferenceStorage[SystemPreferences.SEEN_DONATION_CTA_AMOUNT] = preferenceStorage[SystemPreferences.SEEN_DONATION_CTA_AMOUNT] + 1
        // set seen time
        preferenceStorage[SystemPreferences.LAST_SEEN_DONATION_CTA] = System.currentTimeMillis()
    }

    fun onDonationSeen(){
        preferenceStorage[SystemPreferences.HAS_DONATED] = true
    }

    fun onDonationCopied(){
        preferenceStorage[SystemPreferences.HAS_COPIED_DONATION_URL] = true
    }

    private fun getHasDonated(): Boolean{
        val debug = textSecurePreferences.hasDonatedDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else preferenceStorage[SystemPreferences.HAS_DONATED]
    }

    private fun getHasCopiedLink(): Boolean{
        val debug = textSecurePreferences.hasCopiedDonationURLDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else preferenceStorage[SystemPreferences.HAS_COPIED_DONATION_URL]
    }

    private fun getSeenCTAAmount(): Int{
        val debug = textSecurePreferences.seenDonationCTAAmountDebug()
        return if(debug != null){
            debug.toInt()
        } else preferenceStorage[SystemPreferences.SEEN_DONATION_CTA_AMOUNT]
    }

    private fun getShowFromReview(): Boolean{
        val debug = textSecurePreferences.showDonationCTAFromPositiveReviewDebug()
        return if(debug != null){
            when(debug){
                TRUE -> true
                else -> false
            }
        } else preferenceStorage[SystemPreferences.SHOW_DONATION_CTA_FROM_POSITIVE_REVIEW]
    }
}