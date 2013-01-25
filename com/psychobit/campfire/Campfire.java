package com.psychobit.campfire;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.DefaultFlag;

/**
 * Campfire is a plugin to remove spawn camping on PvP enabled servers.
 * 
 * It prevents new players from being killed by more experienced players
 * as they try to leave the spawn area. Campfire disables PvP damage, as well 
 * as other player based sources of damage until the new player has found their feet. 
 * @author psychobit
 *
 */
public class Campfire extends JavaPlugin implements Listener
{
	/**
	 * Player data
	 * Contains all the info Campfire needs for a specific player
	 */
	private HashMap<String,PlayerData> _playerData;
	
	/**
	 * Scheduled repeating task
	 * Updates player data on an interval
	 */
	private int _thread;
	
	/**
	 * Time in seconds a player should be protected by campfire
	 * Configurable in the config.yml - defaults to 20 min
	 */
	private int _duration;
	
	/**
	 * Distance around a player that can't be lava'd or set on fire
	 * Configurable in the config.yml - defaults to 5 blocks
	 */
	private int _bufferDist;
	
	/**
	 * Should a player's data be reset upon death?
	 */
	private boolean _resetOnDeath;
	
	/**
	 * Don't count world guard protected areas
	 */
	private boolean _useWorldGuard;
	
	/**
	 * WorldGuard plugin
	 */
	private WorldGuardPlugin _worldguard;
	
	
	/**
	 * Load player data
	 * Register event listener
	 * Start the update player data task
	 */
	public void onEnable()
	{
		// Load the player data
		this._playerData = new HashMap<String,PlayerData>();
		this.loadData();
		
		// Define default config values if not set
		if ( !this.getConfig().contains( "Duration" ) )
		{
			this.getConfig().set( "Duration", 60 * 20 );
			this.getConfig().set( "Buffer", 5 );
			this.getConfig().set( "ResetOnDeath", true );
			this.getConfig().set( "WorldGuardAreas", true );
			this.saveConfig();
		}
		
		// Set the duration and buffer as defined in the config
		this._duration = this.getConfig().getInt( "Duration", 60 * 20 );
		this._bufferDist= this.getConfig().getInt( "Buffer", 5 );
		this._resetOnDeath = this.getConfig().getBoolean( "ResetOnDeath", true );
		this._useWorldGuard = this.getConfig().getBoolean( "WorldGuardAreas", true );
		
		// Check for worldguard
		Plugin p = this.getServer().getPluginManager().getPlugin( "WorldGuard" );
		if ( p != null && p instanceof WorldGuardPlugin )
		{
			System.out.println( "[Campfire] Found WorldGuard" );
			this._worldguard= ( WorldGuardPlugin ) p;
		}
		
		// Register events
		this.getServer().getPluginManager().registerEvents( this, this );
		
		// Start the task to update player data
        final Campfire plugin = this;
        this._thread = this.getServer().getScheduler().scheduleAsyncRepeatingTask( this, new Runnable() {
            public void run() { plugin.updatePlayerData(); }
        }, 20L, 20L ); // Update every second
	}
	
	/**
	 * Process commands
	 * @param sender Who sent the command
	 * @param command Command that was sent
	 * @param label
	 * @param args Arguments
	 */
	public boolean onCommand( CommandSender sender, Command command, String label, String[] args )
	{
		// Check arguments
		if ( args.length == 0 )
		{
			sender.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Usage: " );
			sender.sendMessage( "/campfire terminate " );
			sender.sendMessage( ChatColor.GRAY + "Removes your protection early" );
			sender.sendMessage( "/campfire timeleft [player] " );
			sender.sendMessage( ChatColor.GRAY + "Gives the duration left for a player's protection" );
			return true;
		}
		
		// Check if the sender was a player
		Player player = null;
		if ( sender instanceof Player ) player = ( Player ) sender;
		
		// Parse the action as defined by the first argument
		if ( args[0].equalsIgnoreCase( "reset" ) )
		{
			// Permission check
			if ( !sender.hasPermission( "campfire.reset" ) )
			{
				sender.sendMessage( ChatColor.RED + "You don't have permission to do that!" );
				return true;
			}
			// Determine who the player they want to check is
			String target = "";
			if ( args.length == 2 )
			{
				// Search for a target
				Player targetPlayer = this.getServer().getPlayer( args[1] );
				if ( targetPlayer != null ) target = targetPlayer.getName();
			} else {
				// Must have a target
				sender.sendMessage( "You must specify a target!" );
				return true;
			}
			
			// Alert if no player was found
			if ( target.equals( "" ) )
			{
				sender.sendMessage( ChatColor.RED + "Player not found!" );
				return true;
			}
			
			// Reset the target
			PlayerData data = this._playerData.get( target );
			data.reset();
			sender.sendMessage( "Player's protection reset!" );
			this.getServer().getPlayer( target ).sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Your protection has been reset!" );
			return true;
		}
		/*
		 * Terminate the player's protection early
		 * Allows players to use chests and deal damage before the protection duration is reached
		 */
		else if ( args[0].equalsIgnoreCase( "terminate" ) )
		{
			// Only allow players to use this command
			if ( player == null )
			{
				sender.sendMessage( "Only in-game players can use that command!" );
				return true;
			}
			
			// Terminate the player's protection if it has not expired
			String playerName = player.getName();
			PlayerData data = this._playerData.get( playerName ); 
			if ( data.isEnabled() )
			{
				// Tell them to confirm
				data.setConfirmed();
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] You will be vulnerable to PvP if you" );
				player.sendMessage( "terminate your protection! If you understand the risk, ");
				player.sendMessage( "type '/campfire confirm' to terminate..." );
				return true;
			} else {
				// Tell them they are already expired
				player.sendMessage( "Your protection has already expired!" );
				return true;
			}
		/*
		 * Actually terminates protection after they confirm it
		 */
		} else if ( args[0].equalsIgnoreCase( "confirm" ) ) {
			// Only allow players to use this command
			if ( player == null )
			{
				sender.sendMessage( "Only in-game players can use that command!" );
				return true;
			}
			// Terminate the player's protection if it has not expired
			String playerName = player.getName();
			PlayerData data = this._playerData.get( playerName ); 
			if ( data.isEnabled() )
			{
				// Check for terminate command
				if ( !data.confirmed() )
				{
					player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Use /campfire terminate first!" );
					return true;
				}
				// Disable their protection
				data.setEnabled( false );
				
				// Announce it to the server
				this.getServer().broadcastMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + playerName + " Terminated their protection!" );
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] You are now vulnerable!" );
				return true;
			} else {
				// Tell them they are already expired
				player.sendMessage( "Your protection has already expired!" );
				return true;
			}
		/*
		 * Allows players to check how much time they have left on their protection,
		 * as well as check the time remaining for other players
		 */
		} else if ( args[0].equalsIgnoreCase( "timeleft" ) ) {
			// Determine who the player they want to check is
			String target = "";
			if ( args.length == 2 )
			{
				// Search for a target
				Player targetPlayer = this.getServer().getPlayer( args[1] );
				if ( targetPlayer != null ) target = targetPlayer.getName();
			} else if ( player != null ) {
				// Default to the issuer's name
				target = player.getName();
			} else {
				// Must have a target
				sender.sendMessage( "You must specify a target!" );
				return true;
			}
			
			// Alert if no player was found
			if ( target.equals( "" ) )
			{
				sender.sendMessage( ChatColor.RED + "Player not found!" );
				return true;
			}
			
			// Check if they have already expired
			PlayerData data = this._playerData.get( target );
			if ( !data.isEnabled() )
			{
				sender.sendMessage( target + ": protection expired!" );
				return true;
			}
			
			// Give them the time left
			int timeLeft = this._duration - data.getTimeElapsed(); 
			int min = ( timeLeft / 60 );
			sender.sendMessage( target + ": " + min + " min of protection left!" );
			return true;
		
		}
		
		// Default to usage
		sender.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Usage: " );
		sender.sendMessage( "/campfire terminate " );
		sender.sendMessage( ChatColor.GRAY + "Removes your protection early" );
		sender.sendMessage( "/campfire timeleft [player] " );
		sender.sendMessage( ChatColor.GRAY + "Gives the duration left for a player's protection" );
		return true;
	}

	
	/**
	 * Save player data and stop the scheduled task
	 */
	public void onDisable()
	{
		this.saveData();
		this.getServer().getScheduler().cancelTask( this._thread );
	}
	
	/**
	 * Save config and player data to disk 
	 */
	public void saveData()
	{
		// Save player data
		try {
			ObjectOutputStream oos = new ObjectOutputStream( new FileOutputStream( this.getDataFolder() + "/players.dat" ) );
			oos.writeObject( this._playerData );
			oos.flush();
			oos.close();
		} catch ( Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Load data from disk
	 */
	@SuppressWarnings("unchecked")
	public void loadData()
	{
		try {
			ObjectInputStream ois = new ObjectInputStream( new FileInputStream( this.getDataFolder() + "/players.dat" ) );
			this._playerData = ( HashMap<String,PlayerData> ) ois.readObject();
			ois.close();
		} catch ( FileNotFoundException e ) { // Ignore it
		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Update players' elapsed time
	 */
	public void updatePlayerData()
	{
		// Loop through online players
		Player[] players = this.getServer().getOnlinePlayers();
		for( Player player : players )
		{
			// Ignore ops
			if ( player.isOp() ) continue;
			
			// Ignore dead players
			if ( player.isDead() ) continue;
			
			// Check if the player is already on the list
			String playerName = player.getName();
			if ( !this._playerData.containsKey( playerName ) )
			{
				// Add them to the list
				PlayerData data = new PlayerData();
				this._playerData.put( playerName, data );
				this._playerData.get( playerName ).setUpdateTime();
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Starting protection!" );
				player.sendMessage( "Type '/campfire' for info on PvP Protection" );
			} else if ( !this._playerData.get( playerName ).isEnabled() ) continue; // Skip over expired players
			
			// Increment their time and update their last updated time
			PlayerData data = this._playerData.get( playerName );
			if ( data.inProtectedZone() )
			{
				data.setUpdateTime();
				return;
			}
			data.update();
			
			
			// Check for expiration
			int timeLeft = this._duration - data.getTimeElapsed();
			if ( timeLeft <= 0 )
			{
				this.getServer().broadcastMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Protection for " + playerName + " Expired!" );
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] You are vulnerable!" );
				data.setEnabled( false );
			} else if ( timeLeft % 60 == 0 ) {
				int min = timeLeft / 60;
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Expires in " + min + " minute" + ( min != 1 ? "s" : "" ) + "!" );
			}
		}
		
		// Save
		this.saveData();
	}
	
	
	/**
	 * Update player's protected zone status
	 * @param e
	 */
	@EventHandler( priority = EventPriority.LOW )
	public void onMove( PlayerMoveEvent e )
	{
		// Basic checks
		if ( !this._useWorldGuard ) return;
		if ( this._worldguard == null ) return;
		if ( e.isCancelled() ) return;
		
		// Alias
		Player player = e.getPlayer();
		String playerName = player.getName();
		PlayerData data = this._playerData.get( playerName );
		
		// Ignore ops
		if ( player.isOp() ) return;
		
		// Ignore expired players
		if ( !data.isEnabled() ) return;
		
		// Check if they are in NoPvP or Invincible regions
		boolean inNoPvP = !this._worldguard.getRegionManager( player.getWorld() ).getApplicableRegions( player.getLocation() ).allows( DefaultFlag.PVP );
		boolean inInvincible = this._worldguard.getRegionManager( player.getWorld() ).getApplicableRegions( player.getLocation() ).allows( DefaultFlag.INVINCIBILITY );
		
		// Send messages on state change and don't update if in a protected zone 
		if ( inNoPvP || inInvincible )
		{
			if ( !data.inProtectedZone() )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Entering protected zone." );
				player.sendMessage( "Protection timer paused!" );
				data.setProtectedZone( true );
			}
		} else if ( data.inProtectedZone() ) {
			player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Leaving protected zone." );
			player.sendMessage( "Protection timer resumed!" );
			data.setProtectedZone( false );
		}
	}
	
	
	
	/**
	 * Mark worldguard as enabled if it is enabled
	 * @param e
	 */
	@EventHandler( priority = EventPriority.LOW )
	public void onPluginLoad( PluginEnableEvent e )
	{
		Plugin p = e.getPlugin();
		if ( p.getDescription().getName().equals( "WorldGuard" ) && p instanceof WorldGuardPlugin )
		{
			System.out.println( "[Campfire] Found WorldGuard!" );
			this._worldguard = ( WorldGuardPlugin ) p; 
		}
	}
	
	/**
	 * Mark worldguard as disabled if it is disabled
	 * @param e
	 */
	@EventHandler( priority = EventPriority.LOW )
	public void onPluginLoad( PluginDisableEvent e )
	{
		Plugin p = e.getPlugin(); 
		if ( p.getDescription().getName().equals( "WorldGuard" ) && p instanceof WorldGuardPlugin )
		{
			System.out.println( "[Campfire] WorldGuard disabled!" );
			this._worldguard = null; 
		}
	}
	
	
	/**
	 * Prevent PvP damage for protected players
	 * @param e
	 */
	@EventHandler( priority = EventPriority.HIGH )
	public void onEntityDamage( EntityDamageEvent e )
	{
		// Make sure the entity is a player
		Player target = null;
		if ( e.getEntity() instanceof Player ) target = ( Player ) e.getEntity();
		if ( target == null ) return;
		
		// Ignore ops
		if ( target.isOp() ) return;
		
		
		// Ensure player was damaged by an entity
		EntityDamageByEntityEvent e2 = null;
		if ( e instanceof EntityDamageByEntityEvent ) e2 = ( EntityDamageByEntityEvent ) e;
		if ( e2 == null ) return;
		
		// Check for tnt
		if ( e2.getDamager() instanceof org.bukkit.entity.TNTPrimed )
		{
			// TNT damage, prevent it
			e.setCancelled( true );
			return;
		}
		
		// Finally, make sure it was another player or an arrow from a player
		Player attacker = null;
		if ( e2.getDamager() instanceof Arrow )
		{
			// Get the arrow's owner
			Arrow arrow = ( Arrow ) e2.getDamager();
			if ( !( arrow.getShooter() instanceof Player ) ) return;
			attacker = ( Player ) arrow.getShooter();
		} else if( !( e2.getDamager() instanceof Player ) ) return;
		else attacker = ( Player ) e2.getDamager(); 
		 
		// Ignore ops
		if ( attacker.isOp() ) return;
		
		// If the attacker or the victim are under protection, cancel the event
		boolean attackerEnabled = false;
		PlayerData attackerData = this._playerData.get( attacker.getName() );
		if ( attackerData != null && attackerData.isEnabled() ) attackerEnabled = true;
		boolean targetEnabled = false;
		PlayerData targetData = this._playerData.get( target.getName() );
		if ( targetData != null && targetData.isEnabled() ) targetEnabled = true;
		if ( attackerEnabled || targetEnabled )
		{
			String message;
			if ( attackerEnabled ) message = "You are under protection! No PvP!";
			else message = "This player is under protection! No PvP!";
			attacker.sendMessage( ChatColor.GRAY + "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.GRAY + "] " + ChatColor.RED + message );
			e.setCancelled( true );
		}
	}
	
	/**
	 * Reset player data when a player dies
	 * Gives them protection back if they die
	 * Disable in the config
	 * @param e
	 */
	@EventHandler( priority = EventPriority.NORMAL )
	public void onEntityDeath( EntityDeathEvent e )
	{
		// Only reset if config says to
		if ( !this._resetOnDeath ) return;
		
		// Make sure it was a player who died
		Player target = null;
		if ( e.getEntity() instanceof Player ) target = ( Player ) e.getEntity();
		if ( target == null ) return;
		
		// Ignore ops
		if ( target.isOp() ) return;
		
		// Reset them
		PlayerData data = this._playerData.get( target.getName() );
		if ( data != null ) data.reset();
		this.saveData();
		
		// Let them know they have been reset
		target.sendMessage( ChatColor.GRAY + "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.GRAY + "] " + "You have died! Resetting Protection!" );
	}
	
	/**
	 * Add players to the player data map if they are new to the server
	 * @param e
	 */
	@EventHandler( priority = EventPriority.HIGH )
	public void onPlayerJoin( PlayerJoinEvent e  )
	{
		// Get player object
		Player player = e.getPlayer();
		String playerName = player.getName();
		
		// Ignore ops
		if ( player.isOp() ) return;
		
		// Add them to the list if they are not on it
		if ( !this._playerData.containsKey( playerName ) )
		{
			// Add them to the list
			PlayerData data = new PlayerData();
			this._playerData.put( playerName, data );
			player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] Starting protection!" );
			player.sendMessage( "Type '/campfire' for info on PvP Protection" );
		}
		
		// Update the player
		this._playerData.get( playerName ).setUpdateTime();
	}
	
	/**
	 * Prevent the use of lava buckets and flint and steel around protected players
	 * @param e
	 */
	@EventHandler( priority = EventPriority.HIGH )
	public void onPlayerInteract( PlayerInteractEvent e )
	{	
		// Get player object
		Player player = e.getPlayer();
		Material itemInHand = player.getItemInHand().getType();
		
		// Ignore ops
		if ( player.isOp() ) return;
		
		// If they are under protection, check if they are trying to use a prohibited item
		if ( this._playerData.get( player.getName() ).isEnabled() )
		{
		
			// Check for flint and steel
			if ( itemInHand.compareTo( Material.FLINT_AND_STEEL ) == 0 )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "You cannot use flint and steel!" );
				player.sendMessage( "Use '/campfire terminate' to end your protection early!" );
				e.setCancelled( true );
				return;
			}
			
			// Check for lava buckets
			if ( itemInHand.compareTo( Material.LAVA_BUCKET ) == 0 )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "You cannot use lava buckets!" );
				player.sendMessage( "Use '/campfire terminate' to end your protection early!" );
				e.setCancelled( true );
				return;
			}
			
			// Check for TNT
			if ( itemInHand.compareTo( Material.TNT ) == 0 )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "You cannot use TNT!" );
				player.sendMessage( "Use '/campfire terminate' to end your protection early!" );
				e.setCancelled( true );
				return;
			}
			
			// Check for chests
			if ( e.getClickedBlock() != null && e.getClickedBlock().getType().compareTo( Material.CHEST ) == 0 )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "You cannot open or break chests!" );
				player.sendMessage( "Use '/campfire terminate' to end your protection early!" );
				e.setCancelled( true );
				return;
			}
			
			// Check for enderchests
			if ( e.getClickedBlock() != null && e.getClickedBlock().getType().compareTo( Material.ENDER_CHEST) == 0 )
			{
				player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "You cannot open or break chests!" );
				player.sendMessage( "Use '/campfire terminate' to end your protection early!" );
				e.setCancelled( true );
				return;
			}
			
			return; // The code below only applies to non-protected players
		}
		
		// Check that the player clicked on a block and that they are holding flint and steel or lava 
		if ( e.getClickedBlock() != null &&
				( itemInHand.compareTo( Material.FLINT_AND_STEEL ) == 0 || 
				itemInHand.compareTo( Material.LAVA_BUCKET ) == 0 ||
				itemInHand.compareTo( Material.TNT ) == 0 ) )
		{
			// Check if they are within the buffer range of protection of a protected player
			Player[] players = this.getServer().getOnlinePlayers();
			for( int i = 0; i < players.length; i++ )
			{
				// Check preliminary conditions
				Player target = players[ i ];
				if ( target.equals( player ) ) continue; // Ignore the player
				if ( target.isOp() ) continue; // Ignore ops
				if ( !target.getWorld().equals( player.getWorld() ) ) continue; // Can't be within range if in different worlds 
				if ( this._playerData.get( target.getName() ) == null ) continue; // We don't have a record of them. Weird, but okay
				if ( !this._playerData.get( target.getName() ).isEnabled() ) continue; // They don't have PvP protection
				
				// Check distance
				double dist = e.getClickedBlock().getLocation().distance( target.getLocation() );
				if ( dist <= this._bufferDist )
				{
					player.sendMessage( "[" + ChatColor.GOLD + "PvP Protection" + ChatColor.WHITE + "] " + ChatColor.RED + "Player is protected!" );
					e.setCancelled( true );
					return;
				}
			}
		}
	}
	
	
	/**
	 * Grant access to playerdata
	 * @param playerName
	 * @return
	 */
	public PlayerData getPlayerData( String playerName )
	{
		if ( this._playerData.containsKey( playerName ) ) return this._playerData.get( playerName );
		return null;
	}
}
