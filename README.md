JavaPiSync
==========
Raspberry Pi (push) Dropbox client in Java

As the Raspberry Pi runs an ARM processor, there is no readily available
Dropbox client. However, a rich Dropbox API is available to write your
own scripts and software.

JavaPiSync has been written to sync a local folder on my Raspberry Pi
towards a Dropbox account, using the Java API. However, as Java code is
portable, it might be used on a variety of platforms. It might also come
in handy for other people trying to understand the Java API, or by
extending this client to fit your own needs.

JavaPiSync will keep its own file where it stores what version (through
an md5-hash) has been uploaded to Dropbox, and which it will also use to
delete no longer present files / folders and to detect new files. It thus
contains the logic required to only sync what needed.

You'll need:
 * The Dropbox Java SDK (https://www.dropbox.com/developers/core/sdks/java)
 * The Apache Commons Codec library (http://commons.apache.org/proper/commons-codec/)
 * A Dropbox account (duh)
 * A Dropbox API key (https://www.dropbox.com/developers/apps)

The client requires no configuration in advance. Whatever it needs, it will
ask for. Just provide it with the appropriate rights to write its configuration
files and read the folder to be synced.

For Java 7, you can readily copy the contents of the dist folder locally
on your device, and run the client as follows:
   java -jar JavaPiSync.jar

Note that the sync is one-way: from the client to Dropbox (not the other way
around), although, once the Account Token is present extending it to other
functions should be pretty straightforward.

The code is not perfect, but it does more or less what it has to do. Might
issues arise, I will correct them best effort, but without any warranty.

Enjoy!
