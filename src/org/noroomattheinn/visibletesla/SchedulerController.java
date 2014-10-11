/*
 * SchedulerController.java - Copyright(c) 2013 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: Sep 7, 2013
 */

package org.noroomattheinn.visibletesla;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.prefs.Preferences;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import org.noroomattheinn.tesla.ChargeState;
import org.noroomattheinn.tesla.Result;
import org.noroomattheinn.tesla.Tesla;
import org.noroomattheinn.tesla.Vehicle;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.visibletesla.ScheduleItem.Command;
import org.noroomattheinn.visibletesla.ThreadManager.Stoppable;

/**
 * FXML Controller class
 *
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class SchedulerController extends BaseController
    implements ScheduleItem.ScheduleOwner, Stoppable {

    private static final int Safe_Threshold = 25;
    
/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/

    @FXML private GridPane gridPane;
    @FXML private TextArea activityLog;
    
    private ChargeState charge;
    private Vehicle v;
    
    private final List<ScheduleItem> schedulers = new ArrayList<>();
    
/*------------------------------------------------------------------------------
 *
 * Implementation of the Stoppable interface
 * 
 *----------------------------------------------------------------------------*/
    
    @Override public void stop() {
        for (ScheduleItem si : schedulers) {
            si.shutDown();
        }
    }

/*------------------------------------------------------------------------------
 *
 * Implementation of the ScheduleOwner interface
 * 
 *----------------------------------------------------------------------------*/
    
    @Override public String getExternalKey() { return v.getVIN(); }
    @Override public Preferences getPreferences() { return appContext.persistentState; }
    @Override public AppContext getAppContext() { return appContext; }
    
    @Override public void runCommand(
            ScheduleItem.Command command, double value,
            MessageTarget messageTarget) {
        if (command != ScheduleItem.Command.SLEEP) {
            if (!wakeAndGetChargeState()) {
                logActivity("Can't wake vehicle - aborting", true);
                return;
            }
        }
        if (!safeToRun(command)) return;
        
        if (!tryCommand(command, value, messageTarget)) {
            tryCommand(command, value, messageTarget);  // Retry to avoid transient errors
        }
    }
    
    private boolean tryCommand(
            ScheduleItem.Command command, double value,
            MessageTarget messageTarget) {
        String name = ScheduleItem.commandToName(command);
        Result r = Result.Succeeded;
        boolean reportActvity = true;
        switch (command) {
            case CHARGE_SET:
            case CHARGE_ON:
                if (value > 0) {
                    r = v.setChargePercent((int)value);
                    if (!(r.success || r.explanation.equals("already_set"))) {
                        logActivity("Unable to set charge target: " + r.explanation, true);
                    }
                }
                if (command == Command.CHARGE_ON)
                    r = v.startCharing();
                break;
            case CHARGE_OFF: r = v.stopCharing(); break;
            case HVAC_ON:
                if (value > 0) {    // Set the target temp first
                    if (appContext.lastKnownGUIState.get().temperatureUnits.equalsIgnoreCase("F"))
                        r = v.setTempF(value, value);
                    else
                        r = v.setTempC(value, value);
                    if (!r.success) break;
                }
                r = v.startAC();
                break;
            case HVAC_OFF: r = v.stopAC();break;
            case AWAKE: appContext.inactivity.setMode(Inactivity.Type.Awake); break;
            case SLEEP: appContext.inactivity.setMode(Inactivity.Type.Sleep); break;
            case UNPLUGGED: r = unpluggedTrigger(); reportActvity = false; break;
            case MESSAGE: r = sendMessage(messageTarget); reportActvity = false; break;
        }
        if (value > 0) name = String.format("%s (%3.1f)", name, value);
        String entry = String.format("%s: %s", name, r.success ? "succeeded" : "failed");
        if (!r.success) entry = entry + ", " + r.explanation;
        logActivity(entry, reportActvity);
        return r.success;
    }
    
    private Result sendMessage(MessageTarget messageTarget) {
        if (messageTarget == null) {
            appContext.utils.sendNotification(
                appContext.prefs.notificationAddress.get(),
                "No subject was specified",
                "No body was specified");
            return Result.Succeeded;
        }
        MessageTemplate body = new MessageTemplate(appContext, messageTarget.getActiveMsg());
        MessageTemplate subj = new MessageTemplate(appContext, messageTarget.getActiveSubj());
        boolean sent = appContext.utils.sendNotification(
            messageTarget.getActiveEmail(),
            subj.getMessage(null),
            body.getMessage(null));
        return sent ? Result.Succeeded : Result.Failed;
    } 
    
    private boolean requiresSafeMode(ScheduleItem.Command command) {
        return (command == ScheduleItem.Command.HVAC_ON);
    }
    
    private boolean safeToRun(ScheduleItem.Command command) {
        if (!requiresSafeMode(command)) return true;
        
        String name = ScheduleItem.commandToName(command);
        if (appContext.prefs.safeIncludesMinCharge.get()) {
            if (charge.batteryPercent < Safe_Threshold) {
                String entry = String.format(
                        "%s: Insufficient charge - aborted", name);
                logActivity(entry, true);
                return false;
            }
        }

        if (appContext.prefs.safeIncludesPluggedIn.get()) {
            String msg;

            switch (ChargeController.getPilotCurent(v, charge)) {
                case -1:
                    msg = String.format("%s: Can't tell if car is plugged in - aborted", name);
                    logActivity(msg, true);
                    return false;
                case 0:
                    msg = String.format("%s: Car is not plugged in - aborted", name);
                    logActivity(msg, true);
                    return false;
                default:
                    return true;
            }
        }
        
        return true;
    }
    
    private boolean wakeAndGetChargeState() {
        appContext.inactivity.setState(Inactivity.Type.Awake);
        charge = v.queryCharge();
        if (charge.valid) return true;
        
        for (int i = 0; i < 20; i++) {
            v.wakeUp();
            if ((charge = v.queryCharge()).valid)
                return true;
            Utils.sleep(5000);
        }
        return false;
    }
    
    private synchronized Result unpluggedTrigger() {
        int pilotCurrent = ChargeController.getPilotCurent(v, charge);
        if (pilotCurrent == 0) {
            appContext.utils.sendNotification(
                appContext.prefs.notificationAddress.get(),
                "Your car is not plugged in. Range = " + (int)charge.range);
            return new Result(true, "Vehicle is unplugged. Notification sent");
        } else if (pilotCurrent == -1) {
            return new Result(true, "Can't tell if car is plugged in. No notification sent");
        }
        return new Result(true, "Vehicle is plugged-in. No notification sent");
    }
    
    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - Loading the UI Elements
 * 
 *----------------------------------------------------------------------------*/

    private void prepareSchedulerUI(GridPane gridPane) {
        Map<Integer,Map<Integer,Node>> rows = loadFromGrid(gridPane);
        
        for (Map.Entry<Integer, Map<Integer, Node>> rowEntry : rows.entrySet()) {
            int rowNum = rowEntry.getKey().intValue();
            Map<Integer, Node> row = rowEntry.getValue();
            schedulers.add(new ScheduleItem(rowNum, row, this));
        }
    }
    
    private Map<Integer,Map<Integer,Node>> loadFromGrid(GridPane gp) {
        Map<Integer,Map<Integer,Node>> rowMap = new HashMap<>();
        ObservableList<Node> kids = gp.getChildren();
        
        for (Node kid : kids) {
            ObservableMap<Object,Object> props = kid.getProperties();
            int columnNumber = getRowOrColumn(kid, false);
            int rowNumber = getRowOrColumn(kid, true);
            if (rowNumber < 0)
                continue;   // -1 isn't in the grid
            rowNumber--;
            Map<Integer,Node> thisRow = rowMap.get(rowNumber);
            if (thisRow == null) {
                thisRow = new HashMap<>();
                rowMap.put(rowNumber, thisRow);                
            }
            thisRow.put(columnNumber, kid);
        }
        return rowMap;
    }
    
    private int getRowOrColumn(Node node, boolean getRow) {
        ObservableMap<Object,Object> props = node.getProperties();
        String propName = getRow ? "gridpane-row" : "gridpane-column";
        Number prop = ((Number)props.get(propName));
        return (prop == null) ? -1 : prop.intValue();
    }

/*------------------------------------------------------------------------------
 *
 * Methods overridden from BaseController
 * 
 *----------------------------------------------------------------------------*/
    
    @Override protected void fxInitialize() {
        // Deep-Six the refresh button and progress indicator
        refreshButton.setDisable(true);
        refreshButton.setVisible(false);
        progressIndicator.setVisible(false);

        prepareSchedulerUI(gridPane);
    }

    @Override protected void initializeState() {
        v = appContext.vehicle;
        appContext.tm.addStoppable(this);
    }
    
    @Override protected void activateTab() {
        for (ScheduleItem item : schedulers) { item.loadExistingSchedule(); }
    }
    
    @Override protected void refresh() { }

    
/*------------------------------------------------------------------------------
 *
 * PRIVATE - Utility Methods
 * 
 *----------------------------------------------------------------------------*/
    
    private void logActivity(String entry, boolean report) {
        Date now = new Date();
        String previousEntries = activityLog.getText();
        String datedEntry = String.format(
            "[%1$tm/%1$td/%1$ty %1$tH:%1$tM] %2$s\n%3$s", now, entry, previousEntries);
        activityLog.setText(datedEntry);
        Tesla.logger.log(Level.FINE, entry);
        if (report) { appContext.schedulerActivity.set(entry); }
    }

}

