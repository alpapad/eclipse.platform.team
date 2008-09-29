/*******************************************************************************
 * Copyright (c) 2008 compeople AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * 	compeople AG (Stefan Liebig) - initial API and implementation
 * 	IBM Corporation - bug 246072, adding IProxyData.source support 
 *******************************************************************************/
package org.eclipse.core.internal.net.proxy.win32.winhttp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.core.internal.net.Activator;
import org.eclipse.core.internal.net.ProxyData;
import org.eclipse.core.net.proxy.IProxyData;

/**
 * A helper class that transforms strings (proxy lists, ..) from the native API
 * into a format suitable for the proxy provider API.
 */
public final class ProxyProviderUtil {

	static private int PROXY_DEFAULT_PORT = 80;
	static private int HTTPPROXY_DEFAULT_PORT = PROXY_DEFAULT_PORT;
	static private int HTTPSPROXY_DEFAULT_PORT = 443;
	static private int SOCKSPROXY_DEFAULT_PORT = 1080;

	private ProxyProviderUtil() {
		super();
	}

	/**
	 * Scan the proxy list string and fill this information in the correct list
	 * or map. <br>
	 * The proxy list contains one or more of the following strings separated by
	 * semicolons:<br>
	 * <code><pre>
	 * ([&lt;scheme&gt;=][&lt;scheme&gt; &quot;://&quot; ]&lt;server&gt;[ &quot;:&quot; &lt;port&gt;])
	 * </pre></code>
	 * 
	 * @param proxyList
	 *            the proxy list as a string
	 * @param universalProxies
	 *            the list of proxies receiving the universal proxies
	 * @param protocolSpecificProxies
	 *            a map from http schemes to the list of scheme specific proxies
	 */
	public static void fillProxyLists(String proxyList, List universalProxies,
			Map protocolSpecificProxies) {
		StringTokenizer tokenizer = new StringTokenizer(proxyList, ";"); //$NON-NLS-1$
		while (tokenizer.hasMoreTokens()) {
			createProxy(tokenizer.nextToken(), universalProxies,
					protocolSpecificProxies);
		}
	}

	private static void createProxy(final String proxyDefinition,
			List universalProxies, Map protocolSpecificProxies) {
		String protocol = null;
		String host = null;
		int port = 0;

		int urlStart = 0;
		// if there is no '=' character within the proxy definition we have a
		// proxy definition that serves all protocols. In this case we MUST
		// ignore the protocol, otherwise the protocol MUST be used to determine
		// the specific proxy settings
		int equalsChar = proxyDefinition.indexOf("="); //$NON-NLS-1$
		if (equalsChar != -1) {
			protocol = proxyDefinition.substring(0, equalsChar).toUpperCase();
			urlStart = equalsChar + 1;
		}

		try {
			// The scheme of the uri is irrelevant. We add the http://
			// scheme to enable class URI to parse the stuff
			String augmentedURI = proxyDefinition.substring(urlStart);
			if (augmentedURI.indexOf("://") == -1) //$NON-NLS-1$
				augmentedURI = "http://" + augmentedURI; //$NON-NLS-1$
			URI uri = new URI(augmentedURI);
			host = uri.getHost();
			port = uri.getPort() > 0 ? uri.getPort()
					: getProxyDefaultPort(protocol);
		} catch (Exception ex) {
			Activator
					.logError(
							"not a valid proxy definition: '" + proxyDefinition + "'.", ex); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}

		if (host == null) {
			Activator
					.logError(
							"not a valid proxy definition: '" + proxyDefinition + "'.", null); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}

		if (protocol == null)
			universalProxies.add(createProxy(IProxyData.HTTP_PROXY_TYPE, host,
					port));
		else
			addProtocolSpecificProxy(protocolSpecificProxies, protocol,
					createProxy(resolveProxyType(protocol), host, port));
	}

	private static int getProxyDefaultPort(String protocol) {
		if (protocol == null)
			return PROXY_DEFAULT_PORT;
		if (IProxyData.HTTP_PROXY_TYPE.equalsIgnoreCase(protocol))
			return HTTPPROXY_DEFAULT_PORT;
		if (IProxyData.HTTPS_PROXY_TYPE.equalsIgnoreCase(protocol))
			return HTTPSPROXY_DEFAULT_PORT;
		if (IProxyData.SOCKS_PROXY_TYPE.equalsIgnoreCase(protocol))
			return SOCKSPROXY_DEFAULT_PORT;

		return PROXY_DEFAULT_PORT;
	}

	private static void addProtocolSpecificProxy(Map protocolSpecificProxies,
			String protocol, IProxyData proxy) {
		List list = (List) protocolSpecificProxies.get(protocol);
		if (list == null) {
			list = new ArrayList();
			protocolSpecificProxies.put(protocol, list);
		}

		list.add(proxy);
	}

	private static String resolveProxyType(String protocol) {
		if (protocol.equalsIgnoreCase("socks") || protocol.equalsIgnoreCase("socket")) //$NON-NLS-1$ //$NON-NLS-2$
			return IProxyData.SOCKS_PROXY_TYPE;
		if (protocol.equalsIgnoreCase("https")) //$NON-NLS-1$ 
			return IProxyData.HTTPS_PROXY_TYPE;
		return IProxyData.HTTP_PROXY_TYPE;
	}

	private static IProxyData createProxy(String scheme, String host, int port) {
		String type = resolveProxyType(scheme);
		IProxyData proxy = new ProxyData(type);
		proxy.setHost(host);
		proxy.setPort(port);
		proxy.setSource("WINDOWS_IE"); //$NON-NLS-1$
		return proxy;
	}

	/*
	 * Pac related helper methods below here.
	 */

	private static final Map PROXY_TYPE_MAP;

	/*
	 * @see "http://wp.netscape.com/eng/mozilla/2.0/relnotes/demo/proxy-live.html"
	 */
	private static final String PAC_PROXY_TYPE_DIRECT = "DIRECT"; //$NON-NLS-1$
	private static final String PAC_PROXY_TYPE_PROXY = "PROXY"; //$NON-NLS-1$
	private static final String PAC_PROXY_TYPE_SOCKS = "SOCKS"; //$NON-NLS-1$
	private static final String NO_PROXY = "DIRECT"; //$NON-NLS-1$

	static {
		// mapping of pacProgram proxy type names to java proxy types:
		final Map temp = new HashMap();
		temp.put(PAC_PROXY_TYPE_DIRECT, NO_PROXY);
		temp.put(PAC_PROXY_TYPE_PROXY, IProxyData.HTTP_PROXY_TYPE);
		temp.put(PAC_PROXY_TYPE_SOCKS, IProxyData.SOCKS_PROXY_TYPE);
		PROXY_TYPE_MAP = Collections.unmodifiableMap(temp);
	}

	/**
	 * @param pacFindProxyForUrlResult
	 * @return a list of IProxyData objects
	 */
	public static List getProxies(String pacFindProxyForUrlResult) {
		if (pacFindProxyForUrlResult == null
				|| pacFindProxyForUrlResult.trim().length() == 0)
			return Collections.EMPTY_LIST;

		final List result = new ArrayList();
		final StringTokenizer scanner = new StringTokenizer(
				pacFindProxyForUrlResult, ";"); //$NON-NLS-1$
		while (scanner.hasMoreTokens()) {
			final String pacProxy = scanner.nextToken().trim();
			final IProxyData proxy = getProxy(pacProxy);
			if (proxy != null)
				result.add(proxy);
		}

		return result;
	}

	private static IProxyData getProxy(String pacProxy) {
		if (pacProxy == null || pacProxy.length() == 0)
			return null;

		if (!startsWithProxyType(pacProxy))
			// Assume "PROXY" type!
			pacProxy = "PROXY " + pacProxy; //$NON-NLS-1$
		StringTokenizer scanner = new StringTokenizer(pacProxy);
		String pacProxyType = scanner.nextToken();
		String proxyType = (String) PROXY_TYPE_MAP.get(pacProxyType);
		if (proxyType == null || proxyType.equals(NO_PROXY))
			return null;

		String pacHostnameAndPort = null;
		if (scanner.hasMoreTokens())
			pacHostnameAndPort = scanner.nextToken();
		String hostname = getHostname(pacHostnameAndPort);
		if (hostname != null) {
			int port = getPort(pacHostnameAndPort);
			IProxyData proxy = new ProxyData(proxyType);
			proxy.setHost(hostname);
			proxy.setPort(port);
			proxy.setSource("WINDOWS_IE"); //$NON-NLS-1$
			return proxy;
		}
		return null;
	}

	private static boolean startsWithProxyType(String pacProxy) {
		Iterator iter = PROXY_TYPE_MAP.keySet().iterator();
		while (iter.hasNext())
			if (pacProxy.startsWith((String) iter.next()))
				return true;

		return false;
	}

	private static String getHostname(String pacHostnameAndPort) {
		return pacHostnameAndPort != null ? pacHostnameAndPort.substring(0,
				pacHostnameAndPort.indexOf(':')) : null;
	}

	private static int getPort(String pacHostnameAndPort) {
		return pacHostnameAndPort != null
				&& pacHostnameAndPort.indexOf(':') > -1 ? Integer
				.parseInt(pacHostnameAndPort.substring(pacHostnameAndPort
						.indexOf(':') + 1)) : 0;
	}

	/**
	 * Tests equality of the given strings.
	 * 
	 * @param sequence1
	 *            candidate 1, may be null
	 * @param sequence2
	 *            candidate 2, may be null
	 * @return true if both sequences are null or the sequences are equal
	 */
	public static final boolean equals(final CharSequence sequence1,
			final CharSequence sequence2) {
		if (sequence1 == sequence2)
			return true;
		else if (sequence1 == null || sequence2 == null)
			return false;
		else
			return sequence1.equals(sequence2);
	}

	/**
	 * Replace within <code>source</code> the occurrences of <code>from</code>
	 * with <code>to</code>.<br>
	 * <b>Note:</b> This has the same behavior as the
	 * <code>String.replace()</code> method within JDK 1.5.
	 * 
	 * @param source
	 * @param from
	 * @param to
	 * @return the substituted string
	 */
	public static String replace(String source, String from, String to) {
		if (from.length() == 0)
			return source;
		StringBuffer buffer = new StringBuffer();
		int current = 0;
		int pos = 0;
		while (pos != -1) {
			pos = source.indexOf(from, current);
			if (pos == -1) {
				buffer.append(source.substring(current));
			} else {
				buffer.append(source.substring(current, pos));
				buffer.append(to);
				current = pos + from.length();
			}
		}
		return buffer.toString();
	}

}
