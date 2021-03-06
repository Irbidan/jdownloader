package org.jdownloader.gui.toolbar.action;

import org.appwork.storage.config.handler.BooleanKeyHandler;
import org.jdownloader.captcha.v2.solver.cheapcaptcha.CheapCaptchaSolver;
import org.jdownloader.captcha.v2.solver.cheapcaptcha.CheapCaptchaSolverService;
import org.jdownloader.gui.translate._GUI;

public class CaptchaToogleImageTyperzAction extends AbstractToolbarToggleAction {

    public CaptchaToogleImageTyperzAction() {
        super(CheapCaptchaSolver.getInstance().getService().getConfig()._getStorageHandler().getKeyHandler("enabled", BooleanKeyHandler.class));

        setIconKey("cheapCaptcha");

    }

    @Override
    protected String createTooltip() {
        CheapCaptchaSolverService service = CheapCaptchaSolver.getInstance().getService();
        ;
        return _GUI._.createTooltip_Captcha_Service_toggle(service.getName(), service.getType());
    }

    @Override
    protected String getNameWhenDisabled() {
        CheapCaptchaSolverService service = CheapCaptchaSolver.getInstance().getService();
        ;
        return _GUI._.createTooltip_Captcha_Service_getNameWhenDisabled_(service.getName(), service.getType());
    }

    @Override
    protected String getNameWhenEnabled() {
        CheapCaptchaSolverService service = CheapCaptchaSolver.getInstance().getService();
        ;
        return _GUI._.createTooltip_Captcha_Service_getNameWhenEnabled_(service.getName(), service.getType());
    }

}
