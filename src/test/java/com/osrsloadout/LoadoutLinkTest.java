/*
 * Copyright (c) 2026, osrsloadout
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrsloadout;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * These assert the wire contract with the server, which is the one thing in this plugin that cannot be
 * checked by the compiler and cannot be seen to be wrong from inside the game: a renamed field or a dropped
 * secret just produces a 400 that the plugin is designed to swallow.
 */
public class LoadoutLinkTest
{
	private final Gson gson = new Gson();

	@Test
	public void serialisesTheAgreedUploadFields()
	{
		final String json = LoadoutLink.uploadJson(gson, "Zezima", new TreeSet<>(Arrays.asList(4151, 11834, 12002)), "abc123");
		assertEquals("{\"rsn\":\"Zezima\",\"ids\":[4151,11834,12002],\"secret\":\"abc123\"}", json);
	}

	@Test
	public void serialisesTheAgreedPairFields()
	{
		assertEquals("{\"rsn\":\"Zezima\",\"secret\":\"abc123\"}", LoadoutLink.pairJson(gson, "Zezima", "abc123"));
	}

	@Test
	public void sendsIdsAscending()
	{
		// The server sorts and de-duplicates anyway, but sending a canonical list is what lets the plugin
		// compare one capture against the last and skip a request that would change nothing.
		final String json = LoadoutLink.uploadJson(gson, "Zezima", new TreeSet<>(Arrays.asList(12002, 4151, 4151, 11834)), "k");
		assertTrue(json, json.contains("\"ids\":[4151,11834,12002]"));
	}

	@Test
	public void omitsTheLabelWhenThereIsNone()
	{
		// The display name is a label, not a key, so it has to be genuinely optional rather than sent as the
		// string "null" when nobody is logged in.
		final String json = LoadoutLink.uploadJson(gson, null, Collections.singleton(995), "k");
		assertFalse(json, json.contains("rsn"));
		assertTrue(json, json.contains("\"secret\":\"k\""));
	}

	@Test
	public void escapesDisplayNames()
	{
		// A display name arrives from the game, not from us. Gson is here so that a name carrying a quote or
		// a backslash produces valid JSON rather than a request nobody can reproduce.
		final String json = LoadoutLink.uploadJson(gson, "a\"b\\c", Collections.singleton(995), "k");
		assertTrue(json, json.contains("\"rsn\":\"a\\\"b\\\\c\""));
	}

	@Test
	public void readsThePairingCode()
	{
		assertEquals("3XQQ-W3EM", LoadoutLink.codeFrom(gson, "{\"code\":\"3XQQ-W3EM\",\"expires_in\":600}"));
	}

	@Test
	public void treatsAnUnusablePairResponseAsNoCode()
	{
		// Every caller's answer to a malformed body is the same as its answer to a dropped connection, so
		// these must not throw.
		assertNull(LoadoutLink.codeFrom(gson, "not json at all"));
		assertNull(LoadoutLink.codeFrom(gson, "{\"expires_in\":600}"));
		assertNull(LoadoutLink.codeFrom(gson, "{\"code\":\"\"}"));
		assertNull(LoadoutLink.codeFrom(gson, ""));
	}

	@Test
	public void endpointsAreHttpsAndDistinct()
	{
		// The requests carry the only secret in the system, so plain http would be a downgrade nobody would
		// notice until it mattered.
		assertTrue(LoadoutLink.UPLOAD_ENDPOINT, LoadoutLink.UPLOAD_ENDPOINT.startsWith("https://"));
		assertTrue(LoadoutLink.PAIR_ENDPOINT, LoadoutLink.PAIR_ENDPOINT.endsWith("/bank/pair"));
	}
}
