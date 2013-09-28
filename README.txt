This is a repurposing of James Betker's youtubeglassfeed project for an RSS feed
(for this project, an RSS Twitter feed for #BCTPA)

https://github.com/neonglass/youtubeglassfeed

You probably want to install that wonderful glass app as well. I love it.

For any other RSS feed, you can make your own .barcampfeedconfig and copy it up to your sdcard:

adb push .barcampfeedconfig sdcard/

The contents of which are the same as .youtubeglassconfig from youtubeglassfeed:

barcampFeed={url to any RSS feed}
queryInterval=10

