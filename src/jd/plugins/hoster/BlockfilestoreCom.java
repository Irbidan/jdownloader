//jDownloader - Downloadmanager
//Copyright (C) 2010  JD-Team support@jdownloader.org
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

import java.io.IOException;

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "blockfilestore.com" }, urls = { "https?://www\\.blockfilestore\\.com/down/[a-z0-9\\-]+|https?://www\\.blockfilestoredecrypted\\.com/folder/([a-z0-9\\-]+)/\\d+" }, flags = { 0 })
public class BlockfilestoreCom extends PluginForHost {

    public BlockfilestoreCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void correctDownloadLink(DownloadLink link) {
        link.setPluginPatternMatcher(link.getPluginPatternMatcher().replace("blockfilestoredecrypted.", "blockfilestore.").replaceFirst("(/folder/[a-z0-9\\-]+)/\\d+", "$1"));
    }

    @Override
    public String getAGBLink() {
        return "https://www.blockfilestore.com/terms";
    }

    private boolean isFolder = false;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        if (isFolder) {
            return AvailableStatus.TRUE;
        }
        this.setBrowserExclusive();
        link.setName(new Regex(link.getDownloadURL(), "blockfilestore\\.com/down/([a-z0-9\\-]+)").getMatch(0));
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        correctDownloadLink(downloadLink);
        if (downloadLink.getStringProperty("folderUID", null) != null) {
            isFolder = true;
        }
        br.setFollowRedirects(true);
        br.getHeaders().put("Accept-Language", "en-us;q=0.7,en;q=0.3");
        br.getPage(downloadLink.getPluginPatternMatcher());
        if (isFolder) {
            final Form f = br.getFormbyAction(downloadLink.getStringProperty("folderUID", ""));
            if (f == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            f.put("__EVENTTARGET", Encoding.urlEncode(downloadLink.getStringProperty("fileUID", null)));
            f.put(Encoding.urlEncode("ctl00$txtEmaiLogin"), "");
            f.put(Encoding.urlEncode("ctl00$txtPasswordLogin"), "");
            f.put(Encoding.urlEncode("ctl00$txtEmaiRecovery"), "");
            f.remove("ctl00%24btnLogin");
            f.remove("ctl00%24btnSend");
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, f, false, 1);
        } else {
            final String viewstatekey = br.getRegex("id=\"__VIEWSTATE_KEY\" value=\"([^<>\"]*?)\"").getMatch(0);
            final String eventvalidation = br.getRegex("id=\"__EVENTVALIDATION\" value=\"([^<>\"]*?)\"").getMatch(0);
            if (viewstatekey == null || eventvalidation == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, br.getURL(), "__EVENTTARGET=&__EVENTARGUMENT=&__VIEWSTATE_KEY=" + Encoding.urlEncode(viewstatekey) + "&__VIEWSTATE=&__EVENTVALIDATION=" + Encoding.urlEncode(eventvalidation) + "&ctl00%24ContentPlaceHolder1%24btn_descarga=Download&ctl00%24txtEmaiLogin=&ctl00%24txtPasswordLogin=&ctl00%24txtEmaiRecovery=", false, 1);
        }
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 500) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 500", 1 * 60 * 60 * 1000l);
            }
            br.followConnection();
            if (br.containsHTML("<title>Error") || br.getURL().contains("blockfilestore.com/404")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setFinalFileName(Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection())));
        dl.startDownload();
    }

    @Override
    public void reset() {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

}