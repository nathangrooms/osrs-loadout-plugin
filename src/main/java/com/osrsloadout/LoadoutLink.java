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
import java.util.Collection;

/**
 * Everything about the link between this install and osrsloadout.com: where the bank goes and what the
 * request body looks like.
 *
 * Kept separate from the plugin, and free of RuneLite types, because the request body is the part worth
 * testing and a JSON shape can be asserted without a game client.
 */
final class LoadoutLink
{
	static final String ENDPOINT = "https://yqdqbsbgowqjkjplkrzi.supabase.co/functions/v1/bank";

	private LoadoutLink()
	{
	}

	/**
	 * Gson rather than string concatenation because a display name is attacker-adjacent input: it arrives
	 * from the game, it can contain a non-breaking space, and hand-built JSON is how a stray quote turns into
	 * a malformed request nobody can reproduce.
	 */
	static String json(Gson gson, String rsn, Collection<Integer> ids, String secret)
	{
		return gson.toJson(new Payload(rsn, ids, secret));
	}

	/**
	 * The field names are the wire contract; Gson takes them verbatim. Renaming one silently changes the
	 * request, which is what the test on this class is guarding.
	 */
	private static final class Payload
	{
		private final String rsn;
		private final int[] ids;
		private final String secret;

		private Payload(String rsn, Collection<Integer> ids, String secret)
		{
			this.rsn = rsn;
			this.secret = secret;
			this.ids = new int[ids.size()];

			int i = 0;
			for (int id : ids)
			{
				this.ids[i++] = id;
			}
		}
	}
}
