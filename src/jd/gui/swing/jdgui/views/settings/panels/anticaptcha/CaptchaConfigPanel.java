package jd.gui.swing.jdgui.views.settings.panels.anticaptcha;

import java.awt.Component;
import java.awt.event.ActionEvent;

import javax.swing.Icon;
import javax.swing.JLabel;

import jd.gui.swing.jdgui.views.settings.components.Checkbox;
import jd.gui.swing.jdgui.views.settings.components.SettingsButton;
import jd.gui.swing.jdgui.views.settings.components.Spinner;

import org.appwork.uio.UIOManager;
import org.jdownloader.actions.AppAction;
import org.jdownloader.captcha.v2.ChallengeResponseController;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.settings.AbstractConfigPanel;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.images.NewTheme;
import org.jdownloader.settings.staticreferences.CFG_CAPTCHA;
import org.jdownloader.settings.staticreferences.CFG_SOUND;

public class CaptchaConfigPanel extends AbstractConfigPanel {
    private static final long serialVersionUID = 1L;

    // private CESSettingsPanel psp;

    public String getTitle() {
        return _GUI._.AntiCaptchaConfigPanel_getTitle();
    }

    public CaptchaConfigPanel() {
        super();
        this.addHeader(getTitle(), NewTheme.I().getIcon("ocr", 32));
        this.addDescriptionPlain(_GUI._.AntiCaptchaConfigPanel_onShow_description());

        addPair(_GUI._.AntiCaptchaConfigPanel_AntiCaptchaConfigPanel_sounds(), null, new Checkbox(CFG_SOUND.CAPTCHA_SOUND_ENABLED));
        addPair(_GUI._.AntiCaptchaConfigPanel_AntiCaptchaConfigPanel_countdown_download(), null, new Checkbox(CFG_CAPTCHA.DIALOG_COUNTDOWN_FOR_DOWNLOADS_ENABLED));
        addPair(_GUI._.CaptchaExchangeSpinnerAction_skipbubbletimeout_(), null, new Spinner(CFG_CAPTCHA.CAPTCHA_EXCHANGE_CHANCE_TO_SKIP_BUBBLE_TIMEOUT));
        this.addHeader(_GUI._.CaptchaConfigPanel_order(), NewTheme.I().getIcon(IconKey.ICON_ORDER, 32));
        this.addDescription(_GUI._.CaptchaConfigPanel_order_description());

        SolverOrderTable table;
        SolverOrderContainer container = new SolverOrderContainer(table = new SolverOrderTable());
        this.addPair(_GUI._.AntiCaptchaConfigPanel_AntiCaptchaConfigPanel_reset(), null, new SettingsButton(new AppAction() {
            {
                setIconKey(IconKey.ICON_RESET);
                setName(_GUI._.lit_reset());
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                if (UIOManager.I().showConfirmDialog(0, _GUI._.lit_are_you_sure(), _GUI._.AntiCaptchaConfigPanel_AntiCaptchaConfigPanel_reset_lit_are_you_sure(), new AbstractIcon(IconKey.ICON_QUESTION, 32), _GUI._.lit_yes(), null)) {
                    ChallengeResponseController.getInstance().resetTiming();
                }
            }

        }));
        add(container);
        // this.addHeader(_GUI._.AntiCaptchaConfigPanel_AntiCaptchaConfigPanel_solver(), NewTheme.I().getIcon("share", 32));
        // this.addDescriptionPlain(_GUI._.AntiCaptchaConfigPanel_onShow_description_solver());
        // add(psp = new CESSettingsPanel());
    }

    private Component label(String lbl) {
        JLabel ret = new JLabel(lbl);
        ret.setEnabled(true);
        return ret;
    }

    @Override
    public Icon getIcon() {
        return NewTheme.I().getIcon("ocr", 32);
    }

    @Override
    protected void onShow() {

        super.onShow();
    }

    @Override
    public void save() {

    }

    @Override
    public void updateContents() {

    }
}