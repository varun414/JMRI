// LnClockControl.java

package jmri.jmrix.loconet;

import jmri.implementation.DefaultClockControl;
import java.util.Date;

/**
 * LnClockControl.java
 *
 * Implementation of the Hardware Fast Clock for Loconet
 * <P>
 * This module is based on a GUI module developed by Bob Jacobsen and Alex
 * Shepherd to correct the Loconet fast clock rate and synchronize it with 
 * the internal JMRI fast clock Timebase. The methods that actually send, correct,
 * or receive information from the Loconet hardware are repackaged versions of 
 * their code.
 * <P>
 * The Loconet Fast Clock is controlled by the user via the Fast Clock Setup GUI
 * that is accessed from the JMRI Tools menu.
 * <P>
 * For this implementation, "synchronize" implies "correct", since the two clocks run 
 * at a different rate.
 * <P>
 * Some of the message formats used in this class are Copyright Digitrax, Inc.
 * and used with permission as part of the JMRI project.  That permission
 * does not extend to uses in other software products.  If you wish to
 * use this code, algorithm or these message formats outside of JMRI, please
 * contact Digitrax Inc for separate permission.
 * <hr>
 * This file is part of JMRI.
 * <P>
 * JMRI is free software; you can redistribute it and/or modify it under 
 * the terms of version 2 of the GNU General Public License as published 
 * by the Free Software Foundation. See the "COPYING" file for a copy
 * of this license.
 * <P>
 * JMRI is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License 
 * for more details.
 *
 * @author      Dave Duchamp Copyright (C) 2007
 * @author		Bob Jacobsen, Alex Shepherd
 * @version     $Revision: 1.12 $
 */
public class LnClockControl extends DefaultClockControl implements SlotListener
{

    /**
     * Create a ClockControl object for a Loconet clock
     */
    public LnClockControl(SlotManager sm, LnTrafficController tc) {
        super();
		
		this.sm = sm;
		this.tc = tc;
		// listen for updated slot contents
        if (sm!=null)
            sm.addSlotListener(this);
        else
            log.error("No LocoNet connection available, LnClockControl can't function");
			
		// Get an instance of the internal timebase
        clock = jmri.InstanceManager.timebaseInstance();
		// Create a Timebase listener for Minute change events from the internal clock
        minuteChangeListener = new java.beans.PropertyChangeListener() {
				public void propertyChange(java.beans.PropertyChangeEvent e) {
					newMinute();
				}
			};
		clock.addMinuteChangeListener(minuteChangeListener);
    }
	
	SlotManager sm;
	LnTrafficController tc;
	
	/* Operational variables */
    jmri.Timebase clock = null;
    java.beans.PropertyChangeListener minuteChangeListener = null;
	/* current values of clock variables */
	private int curDays = 0;
	private int curHours = 0;
	private int curMinutes = 0;
	private int curFractionalMinutes = 900;
	private int curRate = 1;
	private int savedRate = 1;
	/* current options and flags */
	private static boolean setInternal = false;   // true if Loconet Clock is the master
	private static boolean synchronizeWithInternalClock = false;
	private static boolean inSyncWithInternalFastClock = false;
	private static boolean timebaseErrorReported = false;
	private static boolean correctFastClock = false;
	private static boolean readInProgress = false;
	/* constants */
	static long mSecPerHour = 3600000;
	static long mSecPerMinute = 60000;
	
	/**
	 * Accessor routines
	 */
	public String getHardwareClockName() {
		return ("Loconet Fast Clock");
	}
	public boolean canCorrectHardwareClock() {return true;}
	public void setRate(double newRate) {
		if (curRate==0) {
			savedRate = (int)newRate;      // clock stopped case
		}
		else {
			curRate = (int)newRate;        // clock running case
			savedRate = curRate;
		}
		setClock();
	}
	public boolean requiresIntegerRate() {return true;}
	public double getRate() {return curRate;}

    @SuppressWarnings("deprecation")
	public void setTime(Date now) {
		curDays = now.getDate();
		curHours = now.getHours();
		curMinutes = now.getMinutes();
		setClock();
	}

    @SuppressWarnings("deprecation")
	public Date getTime() {
		Date tem = clock.getTime();
		int cHours = tem.getHours();
		long cNumMSec = tem.getTime();
		long nNumMSec = ((cNumMSec/mSecPerHour)*mSecPerHour) - (cHours*mSecPerHour) +
                    (curHours*mSecPerHour) + (curMinutes*mSecPerMinute) ;
		// Work out how far through the current fast minute we are
		// and add that on to the time.
		nNumMSec += (long) ( ( ( 915 - curFractionalMinutes) / 915.0 * 60000) ) ;
		return (new Date(nNumMSec));
	}
	public void startHardwareClock(Date now) {
		curRate = savedRate;
		setTime(now);
	}
	public void stopHardwareClock() {
		savedRate = curRate;
		curRate = 0;
		setClock();
	}

    @SuppressWarnings("deprecation")
	public void initializeHardwareClock(double rate, Date now, boolean getTime) {
		synchronizeWithInternalClock= clock.getSynchronize();
		correctFastClock = clock.getCorrectHardware();
		setInternal = !clock.getInternalMaster();
		if (!setInternal && !synchronizeWithInternalClock && !correctFastClock) {
			// No request to interact with hardware fast clock - ignore call
			return;
		}
		if (rate == 0.0) {
			if (curRate!=0) savedRate = curRate;
			curRate = 0;
		}
		else {
			savedRate = (int)rate;
			if (curRate!=0) curRate = savedRate;
		}
		curDays = now.getDate();
		curHours = now.getHours();
		curMinutes = now.getMinutes();
		if (!getTime) {
			setTime(now);
		}
		if (getTime || synchronizeWithInternalClock || correctFastClock) {
			inSyncWithInternalFastClock = false;
			initiateRead();
		}
	}
	
	/**
	 * Requests read of the Loconet fast clock
	 */
	public void initiateRead() {
		if (!readInProgress) {
			sm.sendReadSlot(LnConstants.FC_SLOT);
			readInProgress = true;
		}
	}
		
	/** 
	 * Corrects the Loconet Fast Clock 
	 */
    @SuppressWarnings("deprecation")
	public void newMinute()
    {
		// ignore if waiting on Loconet clock read
		if (!inSyncWithInternalFastClock) return;
		if (correctFastClock || synchronizeWithInternalClock) {
			// get time from the internal clock
			Date now = clock.getTime();
			// skip the correction if minutes is 0 because Logic Rail Clock displays incorrectly
			//		if a correction is sent at zero minutes.
			if (now.getMinutes()!=0) {
				// Set the Fast Clock Day to the current Day of the month 1-31
				curDays = now.getDate();
				// Update current time
				curHours = now.getHours();
				curMinutes = now.getMinutes();
				long millis = now.getTime() ;
				// How many ms are we into the fast minute as we want to sync the
				// Fast Clock Master Frac_Mins to the right 65.535 ms tick
				long elapsedMS = millis % 60000 ;
				double frac_min = elapsedMS / 60000.0 ;
				curFractionalMinutes = 915 - (int)( 915 * frac_min ) ;
				setClock();
			}
		}
		else if (setInternal && !correctFastClock && !synchronizeWithInternalClock) { 
			inSyncWithInternalFastClock = false;
			initiateRead();
		}
    }
		
    /**
     * Handle changed slot contents, due to clock changes.
	 * Can get here three ways: 
	 *			1) clock slot as a result of action by a throttle and
	 *			2) clock slot responding to a read from this module
	 *			3) a slot not involving the clock changing
     * @param s
     */
    @SuppressWarnings("deprecation")
    public void notifyChangedSlot(LocoNetSlot s) {
		// only watch the clock slot
        if (s.getSlot()!= LnConstants.FC_SLOT ) return; 
		// if don't need to know, simply return
		if (!correctFastClock && !synchronizeWithInternalClock && !setInternal) return;
		if (log.isDebugEnabled()) log.debug("slot update "+s);
        // update current clock variables from the new slot contents
        curDays = s.getFcDays();
        curHours = s.getFcHours();
        curMinutes = s.getFcMinutes();
		int temRate = s.getFcRate();
		// reject the new rate if different and not resetting the internal clock
		if ( (temRate != curRate) && !setInternal) setRate(curRate);
		// keep the new rate if different and resetting the internal clock
		else if ( (temRate != curRate) && setInternal) {
			try {
				clock.userSetRate(temRate);
			} 
            catch (jmri.TimebaseRateException e) {
                if (!timebaseErrorReported) {
                    timebaseErrorReported = true;
                    log.warn("Time base exception on setting rate from LocoNet");
                }
            }			
		}	
        curFractionalMinutes = s.getFcFracMins();
		// we calculate a new msec value for a specific hour/minute
		// in the current day, then set that.
		Date tem = clock.getTime();
		int cHours = tem.getHours();
		long cNumMSec = tem.getTime();
		long nNumMSec = ((cNumMSec/mSecPerHour)*mSecPerHour) - (cHours*mSecPerHour) +
                    (curHours*mSecPerHour) + (curMinutes*mSecPerMinute) ;
		// set the internal timebase based on the Loconet clock
        if (readInProgress && !inSyncWithInternalFastClock) {
			// Work out how far through the current fast minute we are
			// and add that on to the time.
            nNumMSec += (long) ( ( ( 915 - curFractionalMinutes) / 915.0 * 60000) ) ;
            clock.setTime(new Date(nNumMSec));
        }
		else if (setInternal) {
			// unsolicited time change from the Loconet
            clock.setTime(new Date(nNumMSec));
		}
		// Once we have done everything else set the flag to say we are in sync 
		inSyncWithInternalFastClock = true;
    }

    /**
     * Push current Clock Control parameters out to LocoNet slot.
     */
    private void setClock() {
		if (setInternal || synchronizeWithInternalClock || correctFastClock) {
			// we are allowed to send commands to the fast clock
			LocoNetSlot s = sm.slot(LnConstants.FC_SLOT);
			s.setFcDays(curDays);
			s.setFcHours(curHours);
			s.setFcMinutes(curMinutes);
			s.setFcRate(curRate);
			s.setFcFracMins(curFractionalMinutes);
			tc.sendLocoNetMessage(s.writeSlot());
		}
    }

    public void dispose() {
        // Drop loconet connection
        if (sm!=null)
            sm.removeSlotListener(this);

		// Remove ourselves from the Timebase minute rollover event
		if (minuteChangeListener!=null) {
			clock.removeMinuteChangeListener( minuteChangeListener );
			minuteChangeListener = null ;
		}
    }

    static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(LnClockControl.class.getName());
}

/* @(#)LnClockControl.java */
