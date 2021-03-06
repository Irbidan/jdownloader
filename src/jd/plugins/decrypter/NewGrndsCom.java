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

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "newgrounds.com" }, urls = { "http://(www\\.)?newgrounds\\.com/((portal/view/|audio/listen/)\\d+|art/view/[A-Za-z0-9\\-_]+/[A-Za-z0-9\\-_]+)" }, flags = { 0 })
public class NewGrndsCom extends PluginForDecrypt {

    public NewGrndsCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String ARTLINK = "http://(www\\.)?newgrounds\\.com/art/view/[A-Za-z0-9\\-_]+/[A-Za-z0-9\\-_]+";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            final DownloadLink offline = createDownloadlink("directhttp://" + parameter);
            offline.setAvailable(false);
            offline.setProperty("offline", true);
            decryptedLinks.add(offline);
            return decryptedLinks;
        }
        boolean directhttp = false;
        String finallink = null;
        String finalfilename = null;
        if (parameter.matches(ARTLINK)) {
            finallink = br.getRegex("id=\"dim_the_lights\" href=\"(http://[^<>\"]*?)\"").getMatch(0);
            directhttp = true;
        } else {
            if (parameter.contains("/audio/listen/")) {
                final String fid = new Regex(parameter, "(\\d+)$").getMatch(0);
                finalfilename = br.getRegex("property=\"og:title\" content=\"([^<>\"]*?)\"").getMatch(0);
                if (finalfilename != null) {
                    finalfilename = Encoding.htmlDecode(finalfilename).trim() + "_" + fid + ".mp3";
                }
                finallink = "http://www.newgrounds.com/audio/download/" + fid;
                directhttp = true;
            } else {
                if (br.containsHTML("requires a Newgrounds account to play\\.<")) {
                    logger.info("Account needed to decrypt this link: " + parameter);
                    return decryptedLinks;
                }
                finallink = br.getRegex("\"src\":[\t\n\r ]+\"(http:[^<>\"]*?)\"").getMatch(0);
                // Maybe video or .swf
                if (finallink == null) {
                    finallink = br.getRegex("\"url\":\"(http:[^<>\"]*?)\"").getMatch(0);
                }
                if (finallink != null) {
                    finallink = finallink.replace("\\", "");
                }
            }
        }
        if (finallink == null) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        if (directhttp) {
            finallink = "directhttp://" + finallink;
        }
        final DownloadLink dl = createDownloadlink(finallink);
        if (finalfilename != null) {
            finalfilename = Encoding.htmlDecode(finalfilename).trim();
            dl.setFinalFileName(finalfilename);
        }
        decryptedLinks.add(dl);

        return decryptedLinks;
    }

}
