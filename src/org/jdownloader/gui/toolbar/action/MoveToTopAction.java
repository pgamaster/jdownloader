package org.jdownloader.gui.toolbar.action;

import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.components.packagetable.PackageControllerTable;
import org.jdownloader.gui.views.downloads.DownloadsView;
import org.jdownloader.gui.views.downloads.table.DownloadsTable;
import org.jdownloader.gui.views.downloads.table.DownloadsTableModel;
import org.jdownloader.gui.views.linkgrabber.LinkGrabberTable;
import org.jdownloader.gui.views.linkgrabber.LinkGrabberTableModel;
import org.jdownloader.gui.views.linkgrabber.LinkGrabberView;

import jd.gui.swing.jdgui.interfaces.View;

public class MoveToTopAction extends AbstractMoveAction {

    public MoveToTopAction() {
        setName(_GUI.T.MoveToTopAction_MoveToTopAction());
        setIconKey(IconKey.ICON_GO_TOP);

        setAccelerator(PackageControllerTable.KEY_STROKE_ALT_HOME);
    }

    @Override
    public void onGuiMainTabSwitch(View oldView, View newView) {
        if (newView instanceof DownloadsView) {
            DownloadsTable table = ((DownloadsTable) DownloadsTableModel.getInstance().getTable());
            setDelegateAction(table.getMoveTopAction());
        } else if (newView instanceof LinkGrabberView) {
            LinkGrabberTable table = ((LinkGrabberTable) LinkGrabberTableModel.getInstance().getTable());
            setDelegateAction(table.getMoveTopAction());
        } else {
            setDelegateAction(null);
        }
    }

}
