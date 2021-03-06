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

import java.util.HashMap;
import java.util.Map;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.JDHash;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "slideshare.net" }, urls = { "http://(www\\.)?(slidesharedecrypted\\.net/[a-z0-9\\-_]+/[a-z0-9\\-_]+|slidesharepicturedecrypted\\.net/\\d+)" }, flags = { 2 })
public class SlideShareNet extends PluginForHost {

    public SlideShareNet(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://www.slideshare.net/business/premium/plans?cmp_src=main_nav");
    }

    private static final String APIKEY       = "ZXdvclNoQm0=";
    private static final String SHAREDSECRET = "UjZIRW9VVEQ=";
    private static final String FILENOTFOUND = ">Sorry\\! We could not find what you were looking for|>Don\\'t worry, we will help you get to the right place|<title>404 error\\. Page Not Found\\.</title>";

    @Override
    public String getAGBLink() {
        return "http://www.slideshare.net/terms";
    }

    public void correctDownloadLink(final DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("slidesharedecrypted.net/", "slideshare.net/"));
    }

    private static final String PICTURELINK     = "http://slidesharepicturedecrypted\\.net/\\d+";
    private static final String NOTDOWNLOADABLE = "class=\"sprite iconNoDownload j\\-tooltip\"";

    // TODO: Implement API: http://www.slideshare.net/developers/documentation
    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        if (link.getBooleanProperty("offline", false)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (link.getDownloadURL().matches(PICTURELINK)) {
            final String directlink = link.getStringProperty("directpiclink", null);
            link.setFinalFileName(link.getStringProperty("filename", null));
            URLConnectionAdapter con = null;
            try {
                con = br.openGetConnection(directlink);
                if (!con.getContentType().contains("html")) {
                    link.setDownloadSize(con.getLongContentLength());
                } else {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
            } finally {
                try {
                    con.disconnect();
                } catch (Throwable e) {
                }
            }
        } else {
            try {
                br.getPage(link.getDownloadURL());
            } catch (final Exception e) {
                if (br.getHttpConnection().getResponseCode() == 410) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                } else {
                    throw e;
                }
            }
            if (br.containsHTML(FILENOTFOUND) || br.containsHTML(">Uploaded Content Removed<")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            final String filename = br.getRegex("<title>([^<>\"]*?)</title>").getMatch(0);
            if (filename == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            link.setName(Encoding.htmlDecode(filename.trim()));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        // TODO: Check if anything is downloadable without account (easy to check via API)
        if (downloadLink.getDownloadURL().matches(PICTURELINK)) {
            final String directlink = downloadLink.getStringProperty("directpiclink", null);
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, directlink, true, 1);
            if (dl.getConnection().getContentType().contains("html")) {
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            // Set correct ending
            fixFilename(downloadLink);
            dl.startDownload();
        } else {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
    }

    private static final String MAINPAGE = "http://slideshare.net";
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
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            br.setCookie(MAINPAGE, key, value);
                        }
                        return;
                    }
                }
                br.setFollowRedirects(false);
                br.getHeaders().put("Accept-Language", "en-us;q=0.7,en;q=0.3");
                br.getPage("https://www.slideshare.net/login");
                final String token = br.getRegex("name=\"authenticity_token\" type=\"hidden\" value=\"([^<>\"]*?)\"").getMatch(0);
                final String lang = System.getProperty("user.language");
                if (token == null) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.postPage("https://www.slideshare.net/login", "login_source=login.page&remember=1&source_from=&utf8=%E2%9C%93&authenticity_token=" + Encoding.urlEncode(token) + "&user_login=" + Encoding.urlEncode(account.getUser()) + "&user_password=" + Encoding.urlEncode(account.getPass()));
                if (!br.containsHTML("\"success\":true") || br.getCookie(MAINPAGE, "logged_in") == null) {
                    if ("de".equalsIgnoreCase(lang)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                final String is_pro = br.getCookie(MAINPAGE, "is_pro");
                if (is_pro != null && !is_pro.equals("false")) {
                    /* Do not accept unsupported accounts! */
                    logger.info("Premium accounts are not (yet) supported, please contact us in our supportforum!");
                    final AccountInfo ai = new AccountInfo();
                    ai.setStatus("Premium accounts are not (yet) supported, please contact us in our supportforum!");
                    account.setAccountInfo(ai);
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
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
        AccountInfo ai = new AccountInfo();
        try {
            login(this.br, account, true);
        } catch (final PluginException e) {
            account.setValid(false);
            throw e;
        }
        ai.setUnlimitedTraffic();
        account.setValid(true);
        ai.setStatus("Registered (free) User");
        return ai;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        login(this.br, account, false);
        br.setFollowRedirects(false);
        String dllink = null;
        if (link.getDownloadURL().matches(PICTURELINK)) {
            dllink = link.getStringProperty("directpiclink", null);
        } else {
            final boolean useAPI = false;
            if (useAPI) {
                // NOTE: This can also be used without username and password to check links but we always have to access the normal link in
                // order to get this stupid slideshow_id
                br.getPage(link.getDownloadURL());
                final String slideshareID = br.getRegex("\"slideshow_id\":(\\d+)").getMatch(0);
                if (slideshareID == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String timestamp = System.currentTimeMillis() + "";
                // Examplelink: http://www.slideshare.net/webbmedia/webbmedia-group-2013-tech-trends
                final String getLink = "https://www.slideshare.net/api/2/get_slideshow?api_key=" + Encoding.Base64Decode(APIKEY) + "&ts=" + timestamp + "&hash=" + JDHash.getSHA1(Encoding.Base64Decode(SHAREDSECRET) + timestamp) + "&slideshow_id=" + slideshareID;
                br.getPage(getLink);
                dllink = getXML("DownloadUrl");
            } else {
                br.getPage(link.getDownloadURL());
                if (br.containsHTML(NOTDOWNLOADABLE)) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "This document is not downloadable");
                }
                br.getPage(link.getDownloadURL() + "/download");
                if (br.containsHTML(FILENOTFOUND)) {
                    throw new PluginException(LinkStatus.ERROR_FATAL, "This document is not downloadable!");
                }
                dllink = br.getRegex("class=\"altDownload\">[\t\n\r ]+<a class=\"btn\" href=\"(http[^<>\"]*?)\"").getMatch(0);
                if (dllink == null) {
                    dllink = br.getRegex("\"(http://[a-z0-9]+\\.amazonaws\\.com/[^<>\"]*?)\"").getMatch(0);
                }
            }
        }
        if (dllink == null) {
            logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, Encoding.htmlDecode(dllink), true, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // Set correct ending
        fixFilename(link);
        dl.startDownload();
    }

    private void fixFilename(final DownloadLink downloadLink) {
        String oldName = downloadLink.getFinalFileName();
        if (oldName == null) {
            oldName = downloadLink.getName();
        }
        final String serverFilename = Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection()));
        final String newExtension = serverFilename.substring(serverFilename.lastIndexOf("."));
        if (newExtension != null && !oldName.endsWith(newExtension)) {
            String oldExtension = null;
            if (oldName.contains(".")) {
                oldExtension = oldName.substring(oldName.lastIndexOf("."));
            }
            if (oldExtension != null && oldExtension.length() <= 5) {
                downloadLink.setFinalFileName(oldName.replace(oldExtension, newExtension));
            } else {
                downloadLink.setFinalFileName(oldName + newExtension);
            }
        }
    }

    private String getXML(final String parameter) {
        return br.getRegex("<" + parameter + "( type=\"[^<>\"/]*?\")?>([^<>]*?)</" + parameter + ">").getMatch(1);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

}