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

import java.util.Collection;
import java.util.TreeSet;

/**
 * The wire format between this plugin and osrsloadout.com.
 *
 * The site is static and has no backend, so the only channel available is a URL the player carries across
 * themselves. Everything after the '#' is fragment, which browsers never put on the wire, so even the act of
 * opening the link tells the site's host nothing about what is in it.
 *
 * The site's own share links are "v1~", which indexes into the site's internal item array. A plugin cannot
 * know those indexes and they move whenever the site's item data is regenerated. "v2~" exists for this
 * plugin specifically and is keyed on Grand Exchange item ids, which are the one name for an item that the
 * game and the site already agree on. The site skips ids it has no item for, so sending a whole bank is
 * safe.
 *
 * This class holds no RuneLite types on purpose: the format is the part worth testing, and a pure function
 * can be tested without a game client.
 */
final class LoadoutLink
{
	static final String BASE_URL = "https://www.osrsloadout.com/";

	private static final String VERSION = "v2";

	/**
	 * Base 36 rather than decimal because item ids run to five digits and a full bank is a thousand of them;
	 * base 36 is the largest radix both Integer.toString and JavaScript's Number#toString agree on, which is
	 * what lets the site decode this with a bare parseInt(x, 36).
	 */
	private static final int RADIX = 36;

	private LoadoutLink()
	{
	}

	/**
	 * Ascending and de-duplicated is not cosmetic. It makes the string a canonical function of the item set,
	 * which is what lets the caller compare two packings to decide whether anything actually changed.
	 *
	 * Non-positive ids are dropped here as well as at the call site, so this invariant holds no matter who
	 * calls it.
	 */
	static String pack(Collection<Integer> itemIds)
	{
		// TreeSet gives the sort and the de-duplication in one pass, on Integer's natural (numeric) order.
		final TreeSet<Integer> unique = new TreeSet<>();
		for (Integer id : itemIds)
		{
			if (id != null && id > 0)
			{
				unique.add(id);
			}
		}

		final StringBuilder sb = new StringBuilder(VERSION).append('~');
		boolean first = true;
		for (int id : unique)
		{
			if (!first)
			{
				sb.append('.');
			}
			sb.append(Integer.toString(id, RADIX));
			first = false;
		}
		return sb.toString();
	}

	static String url(String packed)
	{
		return BASE_URL + "#" + packed;
	}
}
