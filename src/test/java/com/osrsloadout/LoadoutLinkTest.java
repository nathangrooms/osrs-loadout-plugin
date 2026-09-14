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
import java.util.TreeMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The JSON the plugin sends, which the server reads by these exact field names. */
public class LoadoutLinkTest
{
	private final Gson gson = new Gson();

	private static TreeMap<Integer, Long> items(long... idsThenQuantities)
	{
		final TreeMap<Integer, Long> map = new TreeMap<>();
		for (int i = 0; i < idsThenQuantities.length; i += 2)
		{
			map.put((int) idsThenQuantities[i], idsThenQuantities[i + 1]);
		}
		return map;
	}

	@Test
	public void serialisesTheUploadFields()
	{
		final String json = LoadoutLink.uploadJson(gson, items(453, 2400, 536, 180, 1515, 5000, 4151, 1),
			null, null, "abc123");
		assertEquals("{\"ids\":[453,536,1515,4151],\"qty\":[2400,180,5000,1],\"secret\":\"abc123\"}", json);
	}

	@Test
	public void carriesTheBankLayoutWhenThereIsOne()
	{
		final String json = LoadoutLink.uploadJson(gson, items(995, 12),
			new int[]{4151, 0, -995}, new int[]{2, 1, 0, 0, 0, 0, 0, 0, 0}, "k");
		assertEquals("{\"ids\":[995],\"qty\":[12],\"bank\":[4151,0,-995],"
			+ "\"tabs\":[2,1,0,0,0,0,0,0,0],\"secret\":\"k\"}", json);
	}

	@Test
	public void omitsAnEmptyLayout()
	{
		final String json = LoadoutLink.uploadJson(gson, items(995, 12), new int[0], new int[0], "k");
		assertEquals("{\"ids\":[995],\"qty\":[12],\"secret\":\"k\"}", json);
	}

	@Test
	public void neverSendsAName()
	{
		assertFalse(LoadoutLink.uploadJson(gson, items(995, 12), null, null, "k").contains("rsn"));
		assertEquals("{\"secret\":\"abc123\"}", LoadoutLink.pairJson(gson, "abc123"));
	}

	@Test
	public void keepsQuantitiesAlignedWithIds()
	{
		final TreeMap<Integer, Long> map = new TreeMap<>();
		map.put(12002, 7L);
		map.put(4151, 1L);
		map.put(11834, 3L);
		final String json = LoadoutLink.uploadJson(gson, map, null, null, "k");
		assertTrue(json, json.contains("\"ids\":[4151,11834,12002]"));
		assertTrue(json, json.contains("\"qty\":[1,3,7]"));
	}

	@Test
	public void clampsAnOversizedStack()
	{
		final String json = LoadoutLink.uploadJson(gson, items(995, 5_000_000_000L), null, null, "k");
		assertTrue(json, json.contains("\"qty\":[" + Integer.MAX_VALUE + "]"));
	}

	@Test
	public void readsTheLinkCode()
	{
		assertEquals("3XQQ-W3EM", LoadoutLink.codeFrom(gson, "{\"code\":\"3XQQ-W3EM\",\"expires_in\":600}"));
	}

	@Test
	public void treatsAnUnusableResponseAsNoCode()
	{
		assertNull(LoadoutLink.codeFrom(gson, "not json at all"));
		assertNull(LoadoutLink.codeFrom(gson, "{\"expires_in\":600}"));
		assertNull(LoadoutLink.codeFrom(gson, "{\"code\":\"\"}"));
		assertNull(LoadoutLink.codeFrom(gson, ""));
	}

	@Test
	public void endpointsAreHttps()
	{
		assertTrue(LoadoutLink.UPLOAD_ENDPOINT, LoadoutLink.UPLOAD_ENDPOINT.startsWith("https://www.osrsloadout.com/"));
		assertTrue(LoadoutLink.PAIR_ENDPOINT, LoadoutLink.PAIR_ENDPOINT.endsWith("/bank/pair"));
	}
}
