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
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "8muses.com" }, urls = { "http://(www\\.)?8muses\\.com/index/category/[a-z0-9\\-_]+" }, flags = { 0 })
public class EightMusesComDecrypter extends PluginForDecrypt {

    public EightMusesComDecrypter(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.getPage(parameter);
        if (br.getHttpConnection().getResponseCode() == 404) {
            try {
                decryptedLinks.add(this.createOfflinelink(parameter));
            } catch (final Throwable e) {
                /* Not available in old 0.9.581 Stable */
            }
            return decryptedLinks;
        }
        String fpName = parameter.substring(parameter.lastIndexOf("/") + 1);
        final String[] categories = br.getRegex("(/index/category/[a-z0-9\\-_]+)\" data\\-original\\-title").getColumn(0);
        final String[] links = br.getRegex("(/picture/[^<>\"]*?)\"").getColumn(0);
        if ((links == null || links.length == 0) && (categories == null || categories.length == 0)) {
            logger.warning("Decrypter broken for link: " + parameter);
            return null;
        }
        if (links != null && links.length > 0) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            for (final String singleLink : links) {
                final DownloadLink dl = createDownloadlink("http://www.8muses.com" + singleLink);
                dl._setFilePackage(fp);
                decryptedLinks.add(dl);
            }
        }
        if (categories != null && categories.length > 0) {
            for (final String singleLink : categories) {
                decryptedLinks.add(createDownloadlink("http://www.8muses.com" + singleLink));
            }
        }

        return decryptedLinks;
    }

}
