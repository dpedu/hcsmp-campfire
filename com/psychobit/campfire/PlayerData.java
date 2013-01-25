package com.psychobit.campfire;

import java.io.Serializable;

/**
 * Holds data about players' campfire time
 * @author psychobit
 *
 */
public class PlayerData implements Serializable
{
	/**
	 * Serializable ID
	 */
	private static final long serialVersionUID = -295825367226483171L;

	/**
	 * Timestamp of the last update
	 */
	private long _lastUpdated;
	
	/**
	 * Is the player in a protected zone?
	 */
	private boolean _inProtectedZone;
	
	/**
	 * Amount of time campfire has been enabled so far
	 */
	private int _timeElapsed;
	
	/**
	 * Whether or not campfire is enabled for this person
	 */
	private boolean _disabled;
	
	/**
	 * Whether or not the player has confirmed campfire termination
	 */
	private boolean _confirm;
	
	/**
	 * Set the last updated time 
	 */
	public void setUpdateTime()
	{
		this._lastUpdated = ( System.currentTimeMillis() / 1000 );
	}
	
	/**
	 * Get the last updated time
	 * @return Time player was last updated
	 */
	public long getLastUpdated()
	{
		return this._lastUpdated;
	}
	
	/**
	 * Is the player in a protected zone?
	 * @return
	 */
	public boolean inProtectedZone()
	{
		return this._inProtectedZone;
	}
	
	/**
	 * Get the time elapsed
	 * @return Time elapsed
	 */
	public int getTimeElapsed()
	{
		return this._timeElapsed;
	}
	
	/**
	 * Is campfire enabled for this player
	 * @return Campfire enabled
	 */
	public boolean isEnabled()
	{
		return !this._disabled;
	}
	
	/**
	 * Set whether campfire is enabled or disabled for this player 
	 * @param enabled
	 */
	public void setEnabled( boolean enabled )
	{
		this._disabled = !enabled;
	}
	
	/**
	 * Set whether the player is in a protected zone or not 
	 * @param enabled
	 */
	public void setProtectedZone( boolean enabled )
	{
		this._inProtectedZone = enabled;
	}
	
	
	/**
	 * Update the player's elapsed time
	 */
	public void update()
	{
		if ( this._disabled ) return;
		if ( this._inProtectedZone ) return;
		long currentTime = ( System.currentTimeMillis() / 1000 );
		int inc = ( int ) ( currentTime - this._lastUpdated );
		this._timeElapsed += inc;
		this._lastUpdated = ( System.currentTimeMillis() / 1000 );
	}
	
	/**
	 * Reset the player
	 */
	public void reset()
	{
		this._timeElapsed = 0;
		this._disabled = false;
		this._inProtectedZone = false;
		this._confirm = false;
		this.setEnabled( true );
		this.setUpdateTime();
	}
	
	/**
	 * Set confirmation as available
	 */
	public void setConfirmed()
	{
		this._confirm = true;
	}
	
	/**
	 * Check if the player is ready to confirm termination
	 * @return
	 */
	public boolean confirmed()
	{
		return this._confirm;
	}
}