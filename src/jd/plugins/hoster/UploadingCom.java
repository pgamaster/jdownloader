//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "uploading.com" }, urls = { "http://(www\\.)?uploading\\.com/(files/get/[A-Za-z0-9]+|\\w+/\\w+)" }, flags = { 2 })
public class UploadingCom extends PluginForHost {

    private static AtomicInteger simultanpremium = new AtomicInteger(1);
    private static Object        PREMLOCK        = new Object();
    private String               agent           = null;
    private boolean              free            = false;
    private static final String  CODEREGEX       = "uploading\\.com/(\\w+)";
    private static Object        LOCK            = new Object();
    private static final String  MAINPAGE        = "http://uploading.com/";
    private boolean              loginFail       = false;

    private static final String  TYPE_FILES_GET  = "http://(www\\.)?uploading\\.com/files/get/[A-Za-z0-9]+";
    private static final String  TYPE_FILES      = "http://(www\\.)?uploading\\.com/files/[A-Za-z0-9]+";

    public UploadingCom(PluginWrapper wrapper) {
        super(wrapper);
        this.setStartIntervall(1000l);
        this.enablePremium("http://uploading.com/premium/");
    }

    public void correctDownloadLink(DownloadLink link) {
        if (!link.getDownloadURL().contains("/get")) {
            link.setUrlDownload(link.getDownloadURL().replace("/files", "/files/get").replace("www.", ""));
        }
        if (link.getDownloadURL().contains("/thankyou")) {
            link.setUrlDownload(link.getDownloadURL().replaceFirst("/thankyou/", "/"));
        }
    }

    public void prepBrowser() {
        br.setCookiesExclusive(true);
        // define custom browser headers and language settings.
        br.getHeaders().put("Accept-Language", "en-gb, en;q=0.9, de;q=0.8");
        if (agent == null) {
            /* we first have to load the plugin, before we can reference it */
            JDUtilities.getPluginForHost("mediafire.com");
            agent = jd.plugins.hoster.MediafireCom.stringUserAgent();
        }
        br.getHeaders().put("User-Agent", agent);
    }

    public void checkErrors(Browser br) throws PluginException {
        logger.info("Checking errors");
        if (br.containsHTML("<h2>Daily Download Limit</h2>")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "You have reached your daily downloads limit", 1 * 60 * 60 * 1000l);
        }
        if (br.containsHTML("Sorry, but file you are trying to download is larger then allowed for free download")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Only downloadable via account");
        }
        if (br.containsHTML("YOU REACHED YOUR COUNTRY DAY LIMIT")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, JDL.L("plugins.hoster.uploadingcom.errors.countrylimitreached", "You reached your country daily limit"), 60 * 60 * 1000l);
        }
        if (br.containsHTML("(you have reached your daily download limi|>Ihr heutiges Download\\-Limit wurde erreicht)")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 1 * 60 * 60 * 1000l);
        }
        if (br.containsHTML("Your IP address is currently downloading a file")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 5 * 60 * 1000l);
        }
        if (br.containsHTML("Only Premium users can download files larger than")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Only downloadable via premium");
        }
        if (br.containsHTML("You have reached the daily downloads limit")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 1 * 60 * 60 * 1000l);
        }
        if (br.containsHTML("you can download only one file per")) {
            int wait = 15;
            String time = br.getRegex("you can download only one file per (\\d+) minutes").getMatch(0);
            if (time != null) {
                wait = Integer.parseInt(time.trim());
            }
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, wait * 60 * 1000l);
        }
        if (br.containsHTML("(>Server stopped<|Sorry, the server storing the file is currently unavailable|/> Please try again later\\.)")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.uploadingcom.errors.tempunavailable", "This file is temporary unavailable"), 60 * 60 * 1000l);
        }
    }

    @SuppressWarnings("deprecation")
    public boolean checkLinks(DownloadLink[] urls) {
        if (urls == null || urls.length == 0) {
            return false;
        }
        try {
            ArrayList<DownloadLink> links = new ArrayList<DownloadLink>();
            int index = 0;
            StringBuilder sb = new StringBuilder();
            while (true) {
                sb.delete(0, sb.capacity());
                sb.append("urls=");
                links.clear();
                while (true) {
                    /* we test 100 links at once */
                    if (index == urls.length || links.size() > 100) {
                        break;
                    }
                    correctDownloadLink(urls[index]);
                    links.add(urls[index]);
                    index++;
                }
                int c = 0;
                for (DownloadLink dl : links) {
                    /*
                     * append fake filename , because api will not report anything else
                     */
                    if (c > 0) {
                        sb.append("%0D%0A");
                    }
                    sb.append(Encoding.urlEncode(dl.getDownloadURL()));
                    c++;
                }
                prepBrowser();
                br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
                br.setDebug(true);
                br.postPage("http://uploading.com/filechecker/?ajax", sb.toString());
                String correctedHTML = br.toString().replaceAll("\\\\/", "/").replaceAll("\\\\(r|n|t)", "").replaceAll("\\\\\"", Encoding.htmlDecode("&#34;"));
                for (final DownloadLink dl : links) {
                    final String fileid = new Regex(dl.getDownloadURL(), "uploading\\.com/files/(get/)?([a-z0-9]+)").getMatch(1);
                    if (fileid == null) {
                        logger.warning("Uploading.com availablecheck is broken!");
                        return false;
                    }
                    if (br.containsHTML("are no such files on Uploadi")) {
                        dl.setAvailable(false);
                        continue;
                    }
                    final Regex allMatches = new Regex(correctedHTML, "<div class=\"result clearfix (failed|ok)\">.+http://uploading\\.com/(files/)?(get/)?" + fileid + "/([^/\"]+).*?</a><span class=\"size\">([\\d\\.]+ (B(ytes)|KB|MB|GB))</span></div></div>");
                    final String status = allMatches.getMatch(0);
                    String filename = allMatches.getMatch(3);
                    filename = filename.replaceAll("<|>", "");
                    if (!filename.contains(".") && filename.contains("-")) {
                        final String ext = filename.substring(filename.lastIndexOf("-"));
                        if (ext.length() <= 5) {
                            filename = filename.replace(ext, ext.replace("-", "."));
                        }
                    }
                    String filesize = allMatches.getMatch(4);
                    if (filename == null || filesize == null) {
                        logger.warning("Uploading.com availablecheck is broken!");
                        dl.setAvailable(false);
                    } else if (!status.matches("ok")) {
                        dl.setAvailable(false);
                    } else {
                        dl.setAvailable(true);
                    }
                    filename = Encoding.htmlDecode(filename.trim());
                    filename = Encoding.urlDecode(filename, false);
                    dl.setFinalFileName(filename);
                    dl.setDownloadSize(SizeFormatter.getSize(filesize));
                }
                if (index == urls.length) {
                    break;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        this.setBrowserExclusive();
        AccountInfo ai = new AccountInfo();
        synchronized (LOCK) {
            try {
                login(account, true);
            } catch (PluginException e) {
                account.setValid(false);
                if (account.getAccountInfo() != null) {
                    ai = account.getAccountInfo();
                }
                return ai;
            }
            if (!isPremium(account, true)) {
                account.setValid(true);
                ai.setStatus("Free Membership");
                return ai;
            }
        }
        if (br.getURL() == null || !br.getURL().contains("/account/subscription/")) {
            br.getPage("http://uploading.com/account/subscription/");
        }
        account.setValid(true);
        ai.setStatus("Premium Membership");
        String validUntil = br.getRegex("Valid Until</label>[\n\r\t ]+<span>([^<>]+)").getMatch(0);
        if (validUntil != null) {
            ai.setValidUntil(TimeFormatter.getMilliSeconds(validUntil.trim(), "MMMM dd,yyyy", null));
        } else {
            // not sure about this (old not tested)
            if (br.containsHTML("<span>Lifetime membership</")) {
                /* lifetime accounts */
                ai.setValidUntil(-1);
            } else {
                /* fallback - still valid */
                ai.setValidUntil(br.getCookies(MAINPAGE).get("remembered_user").getExpireDate());
            }
        }
        return ai;
    }

    @Override
    public String getAGBLink() {
        return "http://uploading.com/terms/";
    }

    public String getDownloadUrl(DownloadLink downloadLink, String fileID, String code, String passCode) throws Exception {
        br.setDebug(true);
        String varLink = br.getRegex("var file_link = \\'(http://.*?)\\'").getMatch(0);
        /* captcha may occur here */
        String captcha = "";
        if (br.containsHTML("var captcha_src = \\'http://uploading")) {
            String captchaUrl = "http://uploading.com/general/captcha/download" + fileID + "/?ts=" + System.currentTimeMillis();
            String captchaCode = getCaptchaCode(captchaUrl, downloadLink);
            captcha = "&captcha_code=" + Encoding.urlEncode(captchaCode);
        } else if (passCode != null) {
            captcha = passCode;
        }
        if (varLink != null) {
            sleep(2000, downloadLink);
            return varLink;
        }
        br.setFollowRedirects(false);
        String starttimer = br.getRegex("start_timer\\((\\d+)\\)").getMatch(0);
        String redirect = null;
        if (starttimer != null) {
            sleep((Long.parseLong(starttimer) + 2) * 1000l, downloadLink);
        }
        br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        br.postPage("http://uploading.com/files/get/?ajax", "action=get_link&code=" + code + "&pass=" + captcha);
        redirect = br.getRegex("link\":( )?\"(http.*?)\"").getMatch(1);
        if (redirect != null) {
            redirect = redirect.replaceAll("\\\\/", "/");
        } else {
            if (br.containsHTML("Please wait")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 10 * 1000l);
            }
            if (br.containsHTML("Your download was not found or")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Your download was not found or has expired. Please try again later", 15 * 60 * 1000l);
            }
            if (br.containsHTML("Your download has expired")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Your download was not found or has expired. Please try again later", 15 * 60 * 1000l);
            }
            // Second Password-Errorhandling
            if (br.containsHTML("\"The entered password is incorrect\"")) {
                throw new PluginException(LinkStatus.ERROR_RETRY, "Invalid password");
            }
            if (captcha.length() > 0) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return redirect;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        synchronized (PREMLOCK) {
            return simultanpremium.get();
        }
    }

    @Override
    public int getTimegapBetweenConnections() {
        return 100;
    }

    private void handleDownloadErrors() throws IOException, PluginException {
        logger.info("Handling errors");
        if (dl.getConnection().getResponseCode() == 416) {
            dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "ServerError", 1000l * 60 * 30);
        }
        if (!dl.getConnection().isContentDisposition()) {
            br.followConnection();
            Cookie cookie = dl.getConnection().getRequest().getCookies().get("error");
            String error = null;
            if (cookie != null) {
                error = cookie.getValue();
            }
            if (error == null) {
                error = br.getCookie("http://uploading.com/", "error");
            }
            if (error != null && error.contains("wait")) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, 1000l * 15);
            }
            if (error != null && error.contains("reached")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, 1 * 60 * 60 * 1000l);
            }
            if (br.containsHTML("<h2>Daily Download Limit</h2>")) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "You have reached your daily downloads limit", 1 * 60 * 60 * 1000l);
            }
            if (br.containsHTML("The page you requested was not found")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            logger.warning("dl isn't ContentDisposition, plugin must be broken!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (dl.getConnection().getLongContentLength() == 0) {
            dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "ServerError", 1000l * 60 * 30);
        }
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        if (getAvailableStatus(downloadLink) == AvailableStatus.FALSE) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        handleFree0(downloadLink);
    }

    private AvailableStatus getAvailableStatus(DownloadLink link) {
        try {
            final Field field = link.getClass().getDeclaredField("availableStatus");
            field.setAccessible(true);
            Object ret = field.get(link);
            if (ret != null && ret instanceof AvailableStatus) {
                return (AvailableStatus) ret;
            }
        } catch (final Throwable e) {
        }
        return AvailableStatus.UNCHECKED;
    }

    public void handleFree0(final DownloadLink link) throws Exception {
        final String fileID = get_setFID(link);
        int wait = 60;
        String waitTimer = br.getRegex("<span id=\"timer_secs\">(\\d+)</span>").getMatch(0);
        if (br.containsHTML("that only premium members are")) {
            try {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } catch (final Throwable e) {
                if (e instanceof PluginException) {
                    throw (PluginException) e;
                }
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, "This file can only be downloaded by premium users");
        }
        String passCode = link.getStringProperty("pass", null);
        checkErrors(br);
        Browser ajax = this.br.cloneBrowser();
        ajax.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        ajax.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        if (br.containsHTML("id=\"enter_pass_form\" style=\"display:none\"")) {
            if (passCode == null) {
                passCode = Plugin.getUserInput("Password?", link);
            }
            ajax.postPage("http://uploading.com/files/get/?ajax", "action=check_pass&code=" + fileID + "&pass=" + Encoding.urlEncode(passCode));
            if (ajax.containsHTML("\"error\":\"The entered password is incorrect\"") || !ajax.containsHTML("\"success\":1")) {
                /* handle password has failed in the past, additional try catching / resetting values */
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                link.setProperty("pass", Property.NULL);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
        } else {
            passCode = "false";
        }
        if (waitTimer != null) {
            wait = Integer.parseInt(waitTimer);
        }
        try {
            sleep(wait * 1001l, link);
        } catch (PluginException e) {
            return;
        }
        logger.info("Submitting ajax request");
        ajax.postPage("http://uploading.com/files/get/?ajax", "action=get_link&code=" + Encoding.urlEncode(fileID) + "&pass=" + passCode + "&force_exe=0");
        checkErrors(ajax);
        String nextLink = ajax.getRegex("\"link\":\"(http:[^<>\"]*?)\"").getMatch(0);
        if (nextLink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        nextLink = nextLink.replace("\\", "");
        br.getPage(nextLink);
        checkErrors(br);
        nextLink = br.getRegex("\\'(http://uploading\\.com/files/thankyou/" + fileID + "/[^<>\"]*?)\\'").getMatch(0);
        if (nextLink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        br.getPage(nextLink);
        checkErrors(br);

        String dllink = br.getRegex("\"(http://[a-zA-Z0-9]+\\.uploading\\.com/get_file/[^<>\"]+)\"").getMatch(0);
        if (dllink == null) {
            logger.warning("Can not find final dllink");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 1);
        handleDownloadErrors();
        dl.setFilenameFix(true);
        if (passCode != null) {
            link.setProperty("pass", passCode);
        }
        dl.startDownload();
    }

    private String get_setFID(final DownloadLink dl) {
        String fid;
        if (dl.getDownloadURL().matches(TYPE_FILES_GET)) {
            fid = new Regex(dl.getDownloadURL(), "uploading\\.com/files/get/(.+)").getMatch(0);
        } else {
            fid = new Regex(dl.getDownloadURL(), CODEREGEX).getMatch(0);
        }
        try {
            dl.setLinkID("uploadingcom_" + fid);
        } catch (final Throwable e) {
            /* Not available in old 0.9.581 Stable */
        }
        return fid;
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        free = false;
        br.setDebug(true);
        requestFileInformation(link);
        String passCode = link.getStringProperty("pass");
        synchronized (PREMLOCK) {
            synchronized (LOCK) {
                login(account, false);
                if (!isPremium(account, false)) {
                    simultanpremium.set(1);
                    free = true;
                } else {
                    if (simultanpremium.get() + 1 > 20) {
                        simultanpremium.set(20);
                    } else {
                        simultanpremium.incrementAndGet();
                    }
                }
            }
        }
        String redirect = null;
        br.setFollowRedirects(false);
        br.getPage(link.getDownloadURL());
        if (br.getRedirectLocation() != null) {
            String red = br.getRedirectLocation();
            if (red.contains("get_file/")) {
                redirect = red;
            } else {
                br.getPage(red);
            }
        }
        if (redirect == null) {
            if (free) {
                handleFree0(link);
                return;
            }
            /* <h1>Oops! Seems like you're using Uploading.com via 3rd-party service.</h1> ---------> WTF?? */
            if (br.containsHTML("class=\"error_page wrapper error_403\"")) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Server error 403", 5 * 60 * 1000l);
            }
            final String fid = this.get_setFID(link);
            if (fid == null) {
                logger.warning("code equals null");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (br.containsHTML("Please Enter Password:<")) {
                if (passCode == null) {
                    passCode = Plugin.getUserInput("Password?", link);
                }
                passCode = Encoding.urlEncode(passCode);
            }
            redirect = getDownloadUrl(link, null, fid, passCode);
        }
        br.setFollowRedirects(false);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, redirect, true, 0);
        handleDownloadErrors();
        dl.setFilenameFix(true);
        if (passCode != null) {
            link.setProperty("pass", passCode);
        }
        dl.startDownload();
    }

    public boolean isPremium(Account account, boolean force) throws IOException {
        boolean isPremium = false;
        synchronized (LOCK) {
            boolean follow = br.isFollowingRedirects();
            br.getPage("/account/subscription/");
            br.setFollowRedirects(follow);
            // this needs to be checked
            if (br.containsHTML("UPGRADE TO PREMIUM")) {
                isPremium = false;
            } else if (br.containsHTML("Subscription</label>[\t\n\r ]+<span>Premium</span></li>")) {
                isPremium = true;
            } else {
                isPremium = false;
            }
        }
        return isPremium;
    }

    public void login(Account account, boolean forceLogin) throws Exception {
        try {
            synchronized (LOCK) {
                this.setBrowserExclusive();
                prepBrowser();
                if (!forceLogin) {
                    Object cookiesRet = account.getProperty("cookies");
                    Map<String, String> cookies = null;
                    if (cookiesRet != null && cookiesRet instanceof Map) {
                        cookies = (Map<String, String>) cookiesRet;
                    }
                    if (cookies != null) {
                        if (cookies.containsKey("remembered_user") && account.isValid()) {
                            for (final String key : cookies.keySet()) {
                                this.br.setCookie("http://uploading.com/", key, cookies.get(key));
                            }
                            return;
                        }
                    }
                }
                // so if you load the previously used cookie session it can help
                // with less captcha.
                Object cookiesRet = account.getProperty("cookies");
                Map<String, String> meep = null;
                if (cookiesRet != null && cookiesRet instanceof Map) {
                    meep = (Map<String, String>) cookiesRet;
                }
                if (meep != null) {
                    if (meep.containsKey("remembered_user")) {
                        for (final String key : meep.keySet()) {
                            this.br.setCookie("http://uploading.com/", key, meep.get(key));
                        }
                    }
                }
                // end of previously used cookies //
                br.getPage(MAINPAGE);
                if (br.containsHTML("href=\"http://uploading.com/signout\">Logout</a>")) {
                    // you're already logged in dopey, current cookies are fine.
                    return;
                }
                for (int i = 0; i < 1; i++) {
                    Form login = br.getFormbyProperty("id", "login_form");
                    if (login == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    login.setAction(login.getAction() + "?ajax");
                    login.put("email", account.getUser());
                    login.put("password", account.getPass());
                    login.put("remember", "on");
                    login.put("back_url", "http://uploading.com/");
                    // not sure what triggers recaptcha events. most times you
                    // don't need it but it's always present on login page
                    String recaptcha = br.getRegex("\\(\\'recaptcha_block\\', \\'([^\\']+)").getMatch(0);
                    if (loginFail == true && recaptcha != null) {
                        final Recaptcha rc = new Recaptcha(br, this);
                        rc.setForm(login);
                        String id = recaptcha;
                        rc.setId(id);
                        rc.load();
                        File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                        final DownloadLink dummy = new DownloadLink(this, "uploading.com", "uploading.com", null, true);
                        String c = getCaptchaCode("recaptcha", cf, dummy);
                        Form rcform = rc.getForm();
                        rcform.put("recaptcha_challenge_field", rc.getChallenge());
                        rcform.put("recaptcha_response_field", c);
                        login = rc.getForm();
                    }
                    Browser ajax = this.br.cloneBrowser();
                    ajax.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                    ajax.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
                    ajax.submitForm(login);
                    if (ajax.containsHTML("error\":\"The code entered is incorrect\\.")) {
                        throw new PluginException(LinkStatus.ERROR_CAPTCHA);
                    }
                    // not sure about this
                    if (ajax.containsHTML("captcha\":\"")) {
                        loginFail = true;
                        continue;
                    }
                    // incorrect user:pass {"error":"Incorrect e-mail\/password
                    // combination.<br\/> Please enter correct e-mail and
                    // password."}
                    if (ajax.containsHTML("Please enter correct")) {
                        AccountInfo ai = account.getAccountInfo();
                        if (ai == null) {
                            ai = new AccountInfo();
                            account.setAccountInfo(ai);
                        }
                        ai.setStatus("Please enter correct e-mail and password!");
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                    String url = ajax.getRegex("redirect\":\"(https?[^\"]+)").getMatch(0);
                    if (url != null) {
                        br.getPage(url.replaceAll("\\\\/", "/"));
                    }
                    if (br.containsHTML("uploading\\.com/signout/\">Logout</a>")) {
                        break;
                    } else if (br.containsHTML("<a id=\"login_link\" href=\"#\">Login</a>")) {
                        // we might need captcha?
                        loginFail = true;
                        continue;
                    }
                }
                if (br.getCookie(MAINPAGE, "remembered_user") != null) {
                    final HashMap<String, String> cookies = new HashMap<String, String>();
                    final Cookies add = this.br.getCookies(MAINPAGE);
                    for (final Cookie c : add.getCookies()) {
                        cookies.put(c.getKey(), c.getValue());
                    }
                    account.setProperty("cookies", cookies);
                } else {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
            }
        } catch (PluginException e) {
            account.setProperty("cookies", null);
            throw e;
        }
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink downloadLink) throws Exception {
        setBrowserExclusive();
        prepBrowser();
        br.setFollowRedirects(true);
        correctDownloadLink(downloadLink);
        br.getPage(downloadLink.getDownloadURL());
        if (br.containsHTML("but due to abuse or through deletion by")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("file was removed")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filesize = br.getRegex("<li class=\"size tip_container\">([\\d\\.]+ (B(ytes)?|KB|MB|GB|TB))<").getMatch(0);
        String filename = br.getRegex(">Filemanager</a></li>[\r\n\t ]+<li>([^<>]+)</li>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<meta name=\"keywords\" content=\"([^,]+)").getMatch(0);
            if (filename == null) {
                // Last try to get the filename, if this
                String fname = new Regex(downloadLink.getDownloadURL(), "uploading\\.com/(files/)?(get/)?\\w+/([\\w \\.]+)").getMatch(2);
                fname = fname.replace(" ", "_");
                if (br.containsHTML(fname)) {
                    filename = fname;
                }
            }
        }
        if (filename == null) {
            if (br.containsHTML("<h2>Daily Download Limit</h2>")) {
                return AvailableStatus.UNCHECKABLE;
            }
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        downloadLink.setName(filename.trim());
        if (filesize != null) {
            downloadLink.setDownloadSize(SizeFormatter.getSize(filesize.trim()));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {

    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }
}