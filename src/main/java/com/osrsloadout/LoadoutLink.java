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
import com.google.gson.JsonSyntaxException;
import java.util.Map;
import java.util.SortedMap;
import javax.annotation.Nullable;

/**
 * The two endpoints the plugin calls and the JSON it sends to them. Kept free of RuneLite types so the
 * request bodies can be unit tested.
 */
final class LoadoutLink
{
	static final String UPLOAD_ENDPOINT = "https://www.osrsloadout.com/api/v1/bank";
	static final String PAIR_ENDPOINT = UPLOAD_ENDPOINT + "/pair";

	private LoadoutLink()
	{
	}

	static String uploadJson(Gson gson, SortedMap<Integer, Long> items, int[] slots, int[] tabs, String secret)
	{
		return gson.toJson(new Upload(items, slots, tabs, secret));
	}

	static String pairJson(Gson gson, String secret)
	{
		return gson.toJson(new Pair(secret));
	}

	/** The link code from a pair response, or null if the response is not usable. */
	@Nullable
	static String codeFrom(Gson gson, String body)
	{
		try
		{
			final PairResponse parsed = gson.fromJson(body, PairResponse.class);
			return parsed == null || parsed.code == null || parsed.code.isEmpty() ? null : parsed.code;
		}
		catch (JsonSyntaxException e)
		{
			return null;
		}
	}

	private static final class Upload
	{
		/** Item ids in ascending order, and the quantity of each at the same index. */
		private final int[] ids;
		private final int[] qty;
		/** One id per bank slot (0 empty, negative a placeholder), and the nine tab sizes. Omitted when empty. */
		private final int[] bank;
		private final int[] tabs;
		private final String secret;

		private Upload(SortedMap<Integer, Long> items, int[] slots, int[] tabs, String secret)
		{
			this.bank = slots != null && slots.length > 0 ? slots : null;
			this.tabs = tabs != null && tabs.length > 0 ? tabs : null;
			this.secret = secret;
			this.ids = new int[items.size()];
			this.qty = new int[items.size()];
			int i = 0;
			for (Map.Entry<Integer, Long> entry : items.entrySet())
			{
				final long amount = entry.getValue();
				this.ids[i] = entry.getKey();
				// quantities are summed across containers in a long; clamp rather than wrap
				this.qty[i] = amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
				i++;
			}
		}
	}

	private static final class Pair
	{
		private final String secret;

		private Pair(String secret)
		{
			this.secret = secret;
		}
	}

	private static final class PairResponse
	{
		private String code;
	}
}
