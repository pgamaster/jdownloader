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

package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.Plugin;
import jd.plugins.hoster.DummyScriptEnginePlugin;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "yunpan.cn" }, urls = { "https?://(?:www\\.)?(([a-z0-9]+\\.[a-z0-9]+\\.)?yunpan\\.cn/lk/[A-Za-z0-9]+(?:#\\d+)?(?:\\-0)?(?:\\&downloadpassword=[^<>\"\\&=]+)?|yunpan\\.cn/[a-zA-Z0-9]{13})" }, flags = { 0 })
public class YunpanCn extends antiDDoSForDecrypt {

    public YunpanCn(PluginWrapper wrapper) {
        super(wrapper);
    }

    private String host_with_protocol = null;
    private String parameter          = null;
    private String fid                = null;
    private String passCode           = null;

    @SuppressWarnings({ "unchecked", "rawtypes", "deprecation" })
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        this.br.setFollowRedirects(true);
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        parameter = param.toString();
        fid = new Regex(parameter, "yunpan\\.cn/(?:lk/)?([A-Za-z0-9]+)").getMatch(0);
        final String subfolder_id = new Regex(parameter, "#(\\d+)").getMatch(0);
        passCode = new Regex(parameter, "\\&downloadpassword=([^<>\"\\&=]+)").getMatch(0);
        if (passCode != null) {
            /* Clean added url */
            parameter = parameter.replace("&downloadpassword=" + passCode, "");
        }
        final String json;
        final String host;
        final String host_url = new Regex(parameter, "https?://([^/]+)/").getMatch(0);
        if (host_url.equals("yunpan.cn") || subfolder_id == null) {
            this.br.getPage(parameter);
            host = new Regex(this.br.getURL(), "https?://([^/]+)/").getMatch(0);
            host_with_protocol = "http://" + host;
            /* Usually there was a redirect --> Use that as new url to prevent future redirects */
            parameter = this.br.getURL();
            if (this.br.containsHTML(jd.plugins.hoster.YunPanCn.html_preDownloadPassword)) {
                handlePassword(param);
            }
            json = this.br.getRegex("data:(\\[.*?\\])").getMatch(0);
        } else {
            host = host_url;
            host_with_protocol = "http://" + host;
            if (passCode != null) {
                /* Important - we need that cookie! */
                this.br.getPage(parameter);
                /* ... and that password cookie! */
                handlePassword(param);
            }
            this.br.postPage(host_with_protocol + "/share/listsharedir", "nid=" + subfolder_id + "&surl=" + fid + "&page_size=300&page=0&field=name&order=asc");
            json = this.br.getRegex("data:(\\[.*?\\])").getMatch(0);
        }
        DownloadLink dl = null;
        LinkedHashMap<String, Object> entries = null;
        final ArrayList<Object> ressourcelist = (ArrayList) jd.plugins.hoster.DummyScriptEnginePlugin.jsonToJavaObject(json);
        for (final Object foldero : ressourcelist) {
            entries = (LinkedHashMap<String, Object>) foldero;
            final String nid = (String) entries.get("nid");
            final String name = (String) entries.get("name");
            final String path = (String) entries.get("path");
            final String fhash = (String) entries.get("fhash");
            final long filesize = DummyScriptEnginePlugin.toLong(entries.get("size"), 0);
            String contenturl = "http://" + host + "/lk/" + fid + "#" + nid + "-0";
            if (passCode != null) {
                contenturl += "&downloadpassword=" + passCode;
            }
            if (nid == null || name == null) {
                continue;
            }
            if (fhash == null || fhash.equals("")) {
                /* Subfolder */
                dl = createDownloadlink(contenturl);
            } else {
                /* File */
                dl = createDownloadlink("http://yunpandecrypted.cn/" + System.currentTimeMillis() + new Random().nextInt(100000));
                dl.setDownloadSize(filesize);
                dl.setAvailable(true);
                dl.setFinalFileName(name);
                dl.setProperty("folderid", fid);
                dl.setProperty("fileid", nid);
                dl.setProperty("host", host);
                dl.setProperty("mainlink", parameter);
                dl.setContentUrl(contenturl);
                dl.setLinkID(fid + path);
                if (passCode != null) {
                    dl.setDownloadPassword(passCode);
                }
                dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, path);
            }
            decryptedLinks.add(dl);
        }

        return decryptedLinks;
    }

    @SuppressWarnings("deprecation")
    private void handlePassword(final CryptedLink param) throws IOException, DecrypterException {
        boolean failed = true;
        for (int i = 0; i != 3; i++) {
            if (passCode == null) {
                passCode = Plugin.getUserInput("Password?", param);
            }
            br.postPage(host_with_protocol + "/share/verifyPassword", "shorturl=" + fid + "&linkpassword=" + Encoding.urlEncode(passCode));
            if (br.containsHTML("\"errno\":0,")) {
                failed = false;
                break;
            }
            passCode = null;
        }
        if (failed) {
            throw new DecrypterException(DecrypterException.PASSWORD);
        }
        this.br.getPage(parameter);
    }

}
