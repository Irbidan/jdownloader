//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
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

package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "divshare.com" }, urls = { "http://[\\w\\.]*?divshare\\.com/folder/[0-9a-zA-z-]+" }, flags = { 0 })
public class DvShrCmFldr extends PluginForDecrypt {

    public DvShrCmFldr(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String TYPE_SINGLE_DOWNLOAD = "https?://(www\\.)?divshare\\.com/(?:download|image|direct)/\\d+\\-[a-z0-9]+";

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String parameter = param.toString();
        this.br.setFollowRedirects(false);
        br.getPage(parameter);
        final String redirect = this.br.getRedirectLocation();
        if (redirect != null && redirect.matches(TYPE_SINGLE_DOWNLOAD)) {
            logger.info("Folder redirected to single downloadlink");
            decryptedLinks.add(this.createDownloadlink(redirect));
            return decryptedLinks;
        } else if (redirect != null) {
            this.br.setFollowRedirects(true);
            this.br.getPage(redirect);
        }
        if (br.containsHTML("class=\"download_error_title\"")) {
            decryptedLinks.add(this.createOfflinelink(parameter));
            return decryptedLinks;
        }
        // Liste der Ergebnisse wird gemacht (Regex kann man hier eigentlich
        // genauer machen, ging aber nich anderes bei divshare!)
        final String[] links = br.getRegex("\"(/(download|image|folder).*?)\"").getColumn(0);
        if (links.length == 0) {
            return null;
        }
        for (final String dl : links) {
            // fügt jedem Ergebnis der Liste ein "http://www.divshare.com"
            // hinzu, damit die Links auch angenommen werden
            decryptedLinks.add(createDownloadlink("http://www.divshare.com" + dl));
        }

        return decryptedLinks;
    }

    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}