package org.thoughtcrime.securesms.linkpreview

import junit.framework.TestCase
import org.junit.Test
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil.isLegalUrl

class LinkPreviewUtilTest {
    @Test
    fun isLegal_allAscii_noProtocol() {
        TestCase.assertTrue(isLegalUrl("google.com"))
    }

    @Test
    fun isLegal_allAscii_noProtocol_subdomain() {
        TestCase.assertTrue(isLegalUrl("foo.google.com"))
    }

    @Test
    fun isLegal_allAscii_subdomain() {
        TestCase.assertTrue(isLegalUrl("https://foo.google.com"))
    }

    @Test
    fun isLegal_allAscii_subdomain_path() {
        TestCase.assertTrue(isLegalUrl("https://foo.google.com/some/path.html"))
    }

    @Test
    fun isLegal_cyrillicHostAsciiTld() {
        TestCase.assertFalse(isLegalUrl("http://кц.com"))
    }

    @Test
    fun isLegal_cyrillicHostAsciiTld_noProtocol() {
        TestCase.assertFalse(isLegalUrl("кц.com"))
    }

    @Test
    fun isLegal_mixedHost_noProtocol() {
        TestCase.assertFalse(isLegalUrl("http://asĸ.com"))
    }

    @Test
    fun isLegal_cyrillicHostAndTld_noProtocol() {
        TestCase.assertTrue(isLegalUrl("кц.рф"))
    }

    @Test
    fun isLegal_cyrillicHostAndTld_asciiPath_noProtocol() {
        TestCase.assertTrue(isLegalUrl("кц.рф/some/path"))
    }

    @Test
    fun isLegal_cyrillicHostAndTld_asciiPath() {
        TestCase.assertTrue(isLegalUrl("https://кц.рф/some/path"))
    }

    @Test
    fun isLegal_asciiSubdomain_cyrillicHostAndTld() {
        TestCase.assertFalse(isLegalUrl("http://foo.кц.рф"))
    }

    @Test
    fun isLegal_emptyUrl() {
        TestCase.assertFalse(isLegalUrl(""))
    }
}
