/*
 * Copyright 2012 - 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.handler.codec.frame.FixedLengthFrameDecoder;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.LineBasedFrameDecoder;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.string.StringDecoder;
import org.jboss.netty.handler.codec.string.StringEncoder;
import org.traccar.database.DataManager;
import org.traccar.geocode.GoogleReverseGeocoder;
import org.traccar.geocode.NominatimReverseGeocoder;
import org.traccar.geocode.ReverseGeocoder;
import org.traccar.helper.Log;
import org.traccar.http.WebServer;
import org.traccar.protocol.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

/**
 * Server Manager
 */
public class ServerManager {

	
	//attributes&getters
	
	private final List<TrackerServer> serverList = new LinkedList<TrackerServer>();

	public void addTrackerServer(TrackerServer trackerServer) {
		serverList.add(trackerServer);
	}

	private boolean loggerEnabled;

	public boolean isLoggerEnabled() {
		return loggerEnabled;
	}

	private DataManager dataManager;

	public DataManager getDataManager() {
		return dataManager;
	}

	private ReverseGeocoder reverseGeocoder;

	public ReverseGeocoder getReverseGeocoder() {
		return reverseGeocoder;
	}

	private WebServer webServer;

	public WebServer getWebServer() {
		return webServer;
	}

	private Properties properties;

	public Properties getProperties() {
		return properties;
	}
	
	
	
	

	public void init(String[] arguments) throws Exception {

		// Load properties
		properties = new Properties();
		if (arguments.length > 0) {
			properties.loadFromXML(new FileInputStream(arguments[0]));
		}

		// Init logger
		loggerEnabled = Boolean
				.valueOf(properties.getProperty("logger.enable"));
		if (loggerEnabled) {
			Log.setupLogger(properties);
		}

		dataManager = new DataManager(properties);

		initGeocoder(properties);

		initGt06Server("gt06");

		initProtocolDetector();

		// Initialize web server
		if (Boolean.valueOf(properties.getProperty("http.enable"))) {
			webServer = new WebServer(properties, dataManager);
		}
	}

	public void start() {
		if (webServer != null) {
			webServer.start();
		}
		for (Object server : serverList) {
			((TrackerServer) server).start();
		}
	}

	public void stop() {
		for (Object server : serverList) {
			((TrackerServer) server).stop();
		}

		// Release resources
		GlobalChannelFactory.release();
		GlobalTimer.release();

		if (webServer != null) {
			webServer.stop();
		}
	}

	public void destroy() {
		serverList.clear();
	}

	private void initGeocoder(Properties properties) throws IOException {
		if (Boolean.parseBoolean(properties.getProperty("geocoder.enable"))) {
			String type = properties.getProperty("geocoder.type");
			if (type != null && type.equals("nominatim")) {
				reverseGeocoder = new NominatimReverseGeocoder(getProperties()
						.getProperty("geocoder.url"));
			} else {
				reverseGeocoder = new GoogleReverseGeocoder();
			}
		}
	}

	private boolean isProtocolEnabled(Properties properties, String protocol) {
		String enabled = properties.getProperty(protocol + ".enable");
		if (enabled != null) {
			return Boolean.valueOf(enabled);
		}
		return false;
	}

	private void initProtocolDetector() throws SQLException {
		String protocol = "detector";
		if (isProtocolEnabled(properties, protocol)) {
			serverList.add(new TrackerServer(this, new ServerBootstrap(),
					protocol) {
				@Override
				protected void addSpecificHandlers(ChannelPipeline pipeline) {
					pipeline.addLast("detectorHandler", new DetectorHandler(
							serverList));
				}
			});
			serverList.add(new TrackerServer(this,
					new ConnectionlessBootstrap(), protocol) {
				@Override
				protected void addSpecificHandlers(ChannelPipeline pipeline) {
					pipeline.addLast("detectorHandler", new DetectorHandler(
							serverList));
				}
			});
		}
	}

	
	private void initGt06Server(final String protocol) throws SQLException {
		if (isProtocolEnabled(properties, protocol)) {
			serverList.add(new TrackerServer(this, new ServerBootstrap(),
					protocol) {
				@Override
				protected void addSpecificHandlers(ChannelPipeline pipeline) {
					pipeline.addLast("frameDecoder", new Gt06FrameDecoder());
					pipeline.addLast("objectDecoder", new Gt06ProtocolDecoder(
							dataManager, protocol, properties));
				}
			});
		}
	}

	
}
