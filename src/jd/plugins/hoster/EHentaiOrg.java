//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.File;
import java.util.HashMap;
import java.util.Map.Entry;

import jd.PluginWrapper;
import jd.config.Property;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Browser.BrowserException;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.http.requests.HeadRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "e-hentai.org", "exhentai.org" }, urls = { "http://g\\.e\\-hentai\\.orgdecrypted\\d+", "http://exhentai\\.orgdecrypted\\d+" }, flags = { 2, 2 })
public class EHentaiOrg extends PluginForHost {

    public EHentaiOrg(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://exhentai.org/");
    }

    /* DEV NOTES */
    // Tags:
    // protocol: no https
    // other:

    /* Connection stuff */
    private static final boolean free_resume       = true;
    /* Limit chunks to 1 as we only download small files */
    private static final int     free_maxchunks    = 1;
    private static final int     free_maxdownloads = -1;

    private static final long    minimal_filesize  = 1000;

    private String               DLLINK            = null;
    private final boolean        ENABLE_RANDOM_UA  = true;

    private static final String  TYPE_EHENTAI      = "http://g\\.e\\-hentai\\.orgdecrypted\\d+";
    private static final String  TYPE_EXHENTAI     = "http://exhentai\\.orgdecrypted\\d+";

    @Override
    public String getAGBLink() {
        return "http://g.e-hentai.org/tos.php";
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        final String namepart = downloadLink.getStringProperty("namepart", null);
        DLLINK = null;
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            login(this.br, aa, false);
        } else if (downloadLink.getDownloadURL().matches(TYPE_EXHENTAI)) {
            downloadLink.getLinkStatus().setStatusText("Account needed to check this linktype");
            return AvailableStatus.UNCHECKABLE;
        }
        if (ENABLE_RANDOM_UA) {
            /*
             * Using a different UA for every download might be a bit obvious but at the moment, this fixed the error-server responses as it
             * tricks it into thinking that we re a lot of users and not only one.
             */
            br.getHeaders().put("User-Agent", jd.plugins.hoster.MediafireCom.stringUserAgent());
        }
        br.getPage(getMainlink(downloadLink));
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        DLLINK = br.getRegex("\"(http://\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?/h/[^<>\"]*?)\"").getMatch(0);
        if (DLLINK == null) {
            DLLINK = br.getRegex("src=\"(http://[^<>\"]*?image\\.php\\?[^<>\"]*?)\"").getMatch(0);
        }
        if (DLLINK == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setFinalFileName(namepart + DLLINK.substring(DLLINK.lastIndexOf(".")));
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            try {
                if (isJDStable()) {
                    /* @since JD2 */
                    con = br2.openHeadConnection(DLLINK);
                } else {
                    /* Not supported in old 0.9.581 Stable */
                    con = br2.openGetConnection(DLLINK);
                }
            } catch (final BrowserException ebr) {
                /* Whatever happens - its most likely a server problem for this host! */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 5 * 60 * 1000l);
            }
            if (con.getResponseCode() == 404) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final long conlength = con.getLongContentLength();
            if (!con.getContentType().contains("html") && conlength > minimal_filesize) {
                downloadLink.setDownloadSize(conlength);
                downloadLink.setProperty("directlink", DLLINK);
                return AvailableStatus.TRUE;
            } else {
                return AvailableStatus.UNCHECKABLE;
            }
        } finally {
            if (con != null) {
                if (con.getRequest() instanceof HeadRequest) {
                    br2.loadConnection(con);
                } else {
                    try {
                        con.disconnect();
                    } catch (final Throwable e) {
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        if (downloadLink.getDownloadURL().matches(TYPE_EXHENTAI)) {
            try {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } catch (final Throwable e) {
                if (e instanceof PluginException) {
                    throw (PluginException) e;
                }
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by premium users");
        }
        doFree(downloadLink);
    }

    @SuppressWarnings("deprecation")
    private void doFree(final DownloadLink downloadLink) throws Exception {
        if (downloadLink.getDownloadSize() < minimal_filesize) {
            /* E.h. "403 picture" is smaller than 1 KB */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error - file is too small", 2 * 60 * 1000l);
        }
        try {
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, DLLINK, free_resume, free_maxchunks);
        } catch (final BrowserException ebr) {
            /* Whatever happens - its most likely a server problem for this host! */
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 5 * 60 * 1000l);
        }
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 403) {
                dl.getConnection().disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 60 * 60 * 1000l);
            } else if (dl.getConnection().getResponseCode() == 404) {
                dl.getConnection().disconnect();
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 404", 60 * 60 * 1000l);
            }
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    private static final String MAINPAGE = "http://e-hentai.org";
    private static Object       LOCK     = new Object();

    @SuppressWarnings("unchecked")
    public void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                // Load cookies
                br.setCookiesExclusive(true);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(MAINPAGE, key, value);
                            /* Important! */
                            br.setCookie("http://exhentai.org/", key, value);
                        }
                        return;
                    }
                }
                boolean failed = true;
                br.setFollowRedirects(true);
                br.getPage("https://forums.e-hentai.org/index.php?act=Login&CODE=01");
                for (int i = 0; i <= 3; i++) {
                    final Form loginform = br.getFormbyKey("CookieDate");
                    if (loginform == null) {
                        if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                    }
                    loginform.put("UserName", account.getUser());
                    loginform.put("PassWord", account.getPass());
                    if (i > 0 && br.containsHTML("(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)")) {
                        /* First login try failed and we get a captcha --> Does not necessarily mean that user entered wrong logindata! */
                        final PluginForHost recplug = JDUtilities.getPluginForHost("DirectHTTP");
                        final jd.plugins.hoster.DirectHTTP.Recaptcha rc = ((DirectHTTP) recplug).getReCaptcha(br);
                        rc.findID();
                        rc.load();
                        final DownloadLink dummyLink = new DownloadLink(this, "Account", "e-hentai.org", "http://e-hentai.org", true);
                        final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                        final String c = getCaptchaCode("recaptcha", cf, dummyLink);
                        loginform.put("recaptcha_challenge_field", rc.getChallenge());
                        loginform.put("recaptcha_response_field", c);
                    }
                    br.submitForm(loginform);
                    failed = br.getCookie(MAINPAGE, "ipb_pass_hash") == null;
                    if (!failed) {
                        break;
                    }
                }
                if (failed) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                br.getPage("http://exhentai.org/");
                // Save cookies
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(MAINPAGE);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
            } catch (final PluginException e) {
                account.setProperty("cookies", Property.NULL);
                throw e;
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            login(this.br, account, true);
        } catch (PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        try {
            account.setType(AccountType.PREMIUM);
            account.setConcurrentUsePossible(true);
        } catch (final Throwable e) {
            /* not available in old Stable 0.9.581 */
        }
        ai.setStatus("Premium account");
        account.setValid(true);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        /* No need to login here as we already logged in in availablecheck */
        doFree(link);
    }

    private String getMainlink(final DownloadLink dl) {
        return dl.getStringProperty("individual_link", null);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return free_maxdownloads;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return free_maxdownloads;
    }

    private boolean isJDStable() {
        return System.getProperty("jd.revision.jdownloaderrevision") == null;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
