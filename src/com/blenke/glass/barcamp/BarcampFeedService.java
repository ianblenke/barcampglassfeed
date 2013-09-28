/*
    Barcamp Feed - BarCamp Atom Feed for Google Glass
    Copyright (C) 2013 James Betker

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.blenke.glass.barcamp;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.glass.location.GlassLocationManager;
import com.google.glass.logging.UserEventAction;
import com.google.glass.timeline.TimelineHelper;
import com.google.glass.timeline.TimelineProvider;
import com.google.glass.util.SettingsSecure;
import com.google.googlex.glass.common.proto.MenuItem;
import com.google.googlex.glass.common.proto.MenuValue;
import com.google.googlex.glass.common.proto.NotificationConfig;
import com.google.googlex.glass.common.proto.TimelineItem;
import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

/**
 * A persistant service that maintains a card on the Glass timeline. When turned
 * "on", this service pushes sensor data from a variety of sources onto this card.
 * @author betker
 *
 */
public class BarcampFeedService extends Service{
	static final String TAG = "BarcampFeedService";
	
	//This is just constant because I have no good way of making it configurable in glass.
	final String CONFIGURATION_FILE = "/sdcard/.barcampfeedconfig";
	
	//Shared pref constants
	final String LAST_TWEET_LINK = "LastTweetFed";
	final String SERVICE_ENABLED = "ServiceEnabled";
	
	BarcampFeedService me;
	FeedParser feedParser; //see below for def.
	String feedUrl = "https://script.google.com/macros/s/AKfycbzr4FC377qWZIE5fBUd_nLTudGkXS_4nrHY7SLMRjfxbRk9LbXE/exec?action=search&q=%23BCTPA";
	int updateInterval = 1 * 60 * 1000; //every minute
    
	//States
    boolean enabled = false;
	
	@Override
	public void onCreate(){
		super.onCreate();
		me = this;
		//For the card service
        GlassLocationManager.init(this);
        feedParser = new FeedParser();
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startid){
		super.onStartCommand(intent, flags, startid);
		
		//Attempt to load configuration settings if they exist
		try{
			BufferedReader reader = new BufferedReader(new FileReader(CONFIGURATION_FILE));
			String line = "";
			while((line = reader.readLine()) != null){
				if(line.startsWith("#")) continue; //its a comment
				if(line.startsWith("barcampFeed=")){
					feedUrl = line.replace("barcampFeed=", "");
				}
				if(line.startsWith("queryInterval=")){
					updateInterval = Integer.parseInt(line.replace("queryInterval=", "")) * 60 * 1000;
				}
			}
			Log.v(TAG, "Successfully parsed configuration file. barcampFeed='" + feedUrl + "', queryInterval='" + updateInterval + "'");
			reader.close();
		}catch(Exception e){
			Log.v(TAG, "Error loading configuration file: " + e.getMessage());
		}
		
		//Fetch the enable state
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		enabled = prefs.getBoolean(SERVICE_ENABLED, false);
		if(enabled){
			startFeed();
		}
		
		return START_STICKY;
	}
	
	/**
	 * The Binder class for interfacing between the controller activity and this service.
	 * @author betker
	 *
	 */
	public class ServiceBinder extends Binder{
		public boolean running(){
			return enabled;
		}
		
		public void startup(){
			startFeed();
		}
		
		public void shutdown(){
			stopFeed();
		}
	}
	
	ServiceBinder vBinder = new ServiceBinder();
	@Override
	public IBinder onBind(Intent intent) {
		return vBinder;
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
		stopFeed();
	}

	/**
	 * Starts up the sensors/bluetooth connection and begins pushing data to the timeline.
	 */
	void startFeed(){
		Log.v(TAG, "STARTING BARCAMP FEED SERVICE");
		if(!feedParser.running) (new Thread(feedParser)).start();
		enabled = true;
		//Commit to prefs.
		saveEnableState();
	}
	
	/**
	 * Shuts down sensors/bluetooth.
	 */
	void stopFeed(){
		Log.v(TAG, "STOPPING BARCAMP FEED SERVICE");
		if(feedParser.running) feedParser.stop();
		enabled = false;
		saveEnableState();
	}
	
	void saveEnableState(){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putBoolean(SERVICE_ENABLED, enabled);
		ed.commit();
	}
	
	class FeedParser implements Runnable{
		boolean running = false;
		public void run(){
			running = true;
			while(running){
				parseFeed();
				try{
					Thread.sleep(updateInterval);
				}catch(Exception e){}
			}
		}
		public void stop(){
			running = false;
		}
	}

	//structure for holding Tweet
	class Tweet{
		String link, title;
        public Tweet(String l, String tit){
			link = l; title = tit;
		}
	}
	
	ArrayList<String> parseFeed(){
		Log.v(TAG, "Parsing feed.");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String lastPostedTweetLink = prefs.getString(LAST_TWEET_LINK, "");
		
		try{
			SyndFeedInput input = new SyndFeedInput();
			SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));
			Log.v(TAG, "Feed contains " + feed.getEntries().size() + " items. Last Tweet link = " + lastPostedTweetLink);

            //This list will hold all of the IDs in the RSS stream, which we will then iterate to deliver. We have to do two iterations to make
            //sure we don't re-deliver movies and such.
            ArrayList<Tweet> tweets = new ArrayList<Tweet>();
            List entries = feed.getEntries();
            Iterator iteratorEntries = entries.iterator();
            while (iteratorEntries.hasNext()) {
                SyndEntry entry = (SyndEntry)iteratorEntries.next();
                String title = entry.getTitle(); //this will be the tweet
                Log.v(TAG, "Tweet: " + title);
            }

            while (iteratorEntries.hasNext()) {
                SyndEntry entry = (SyndEntry)iteratorEntries.next();
                String title = entry.getTitle(); //this will be the tweet
                String link = entry.getLink(); //this will be something like twitter.com/BioscanR/statuses/383692371486973952
                if(lastPostedTweetLink != null && lastPostedTweetLink.equals(link)){
                    break; //we've hit the point in the feed where we already were at, don't repost cards.
                }
                tweets.add(new Tweet(link, title));
            }

			Log.v(TAG, "Only " + tweets.size() + " are new tweets.");
			
			//Last but not least, make sure we save the latest tweet we received back into the shared prefs.
			if(tweets.size() > 0){
				//Push the tweets to the timeline
				pushCards(tweets);
				//Save the newest card as the latest read tweet.
				Tweet latestTweet = tweets.get(0);
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(LAST_TWEET_LINK, latestTweet.link);
				ed.commit();
				Log.v(TAG, "Saving last read tweet link: " + latestTweet.link);
			}
		}catch(Exception rss){
			Log.v(TAG, "Failed to fetch feed.");
			rss.printStackTrace();
		}
		return null;
	}
	
	String HTML_B4_TXT = "<article class=\"auto-paginate\"><section><p class=\"text-auto-size\">";
	String HTML_FOOT = "</p></section></article>";
	void pushCards(ArrayList<Tweet> tweets){
    	ContentResolver cr = getContentResolver();
    	//For some reason an TimelineHelper instance is required to call some methods.
    	final TimelineHelper tlHelper = new TimelineHelper();
    	
    	String bundleId = UUID.randomUUID().toString();
    	ArrayList<TimelineItem> cards = new ArrayList<TimelineItem>();
    	
    	//Iterate through all of the new tweets coming in and add them to the same bundle. We are iterating in reverse so that
    	//the newest tweet gets pushed to the top of the stack.
		ListIterator<Tweet> iter = tweets.listIterator(tweets.size());
    	boolean firstCard = true;
    	while(iter.hasPrevious()){
    		Tweet tweet = iter.previous();
    		Log.v(TAG, "BarcampFeed: pushing a card for " + tweet.link);
        	TimelineItem.Builder ntib = tlHelper.createTimelineItemBuilder(me, new SettingsSecure(cr));
        	ntib.setTitle("BarCamp Feed");
            //add the share menu option
            ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.SHARE).setId(UUID.randomUUID().toString()).build());
        	//add the delete menu option
        	ntib.addMenuItem(MenuItem.newBuilder().setAction(MenuItem.Action.DELETE).setId(UUID.randomUUID().toString()).build());
        	ntib.setSendToPhoneUrl(tweet.link);
        	ntib.setText(tweet.title);
        	String html = HTML_B4_TXT + ntib.getText() + HTML_FOOT;
        	ntib.setHtml(html);
        	ntib.setBundleId(bundleId);
        	if(firstCard){
        		ntib.setNotification(NotificationConfig.newBuilder().setLevel(NotificationConfig.Level.DEFAULT));
        	}
        	cards.add(ntib.build());
        	firstCard = false;
    	}
		
    	//Bulk insert the cards
    	tlHelper.bulkInsertTimelineItem(me, cards);
	}
}
