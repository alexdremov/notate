import urllib.request
import json
import re
import os

def hex_to_rgb(hex_color):
    hex_color = hex_color.lstrip('#')
    return tuple(int(hex_color[i:i+2], 16) for i in (0, 2, 4))

def get_more_colors():
    # URL found in search: https://raw.githubusercontent.com/meodai/color-name-lists/main/dist/colorlists.json
    # It seems to be a list of lists.
    # Alternatively, let's look for a flatter structure if possible.
    # But wait, search result [1] says: https://raw.githubusercontent.com/meodai/color-name-lists/main/dist/colorlists.json
    
    url = "https://unpkg.com/color-name-list@1.19.0/dist/colornames.json"
    # Note: I changed colorlists.json to colornames.json based on common naming conventions in that repo 
    # (usually there is a 'names' or 'colornames' file).
    # Let's try the one explicitly found first, but search result [1] URL ends in colorlists.json.
    # Let's try to fetch that.
    
    # url = "https://raw.githubusercontent.com/meodai/color-name-lists/main/dist/colornames.json"
    
    try:
        # User-Agent header might be needed
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode())
            # data structure is likely { hex: "name", ... } or [ {hex, name}, ... ]
            return data
    except Exception as e:
        print(f"Error fetching More colors: {e}")
        return []

def read_existing_file(path):
    with open(path, 'r') as f:
        return f.read()

def main():
    kt_file_path = "app/src/main/java/com/alexdremov/notate/util/ColorNamer.kt"
    
    # 1. Fetch New Colors
    print("Fetching NTC/More colors...")
    new_colors_data = get_more_colors()
    print(f"Fetched {len(new_colors_data)} items (raw).")
    
    # Adapt structure
    # If it is a list of objects:
    parsed_new_colors = []
    if isinstance(new_colors_data, list):
        for item in new_colors_data:
            # Check keys
            name = item.get('name')
            hex_val = item.get('hex')
            if name and hex_val:
                parsed_new_colors.append({'name': name, 'hex': hex_val})
    elif isinstance(new_colors_data, dict):
         for hex_val, name in new_colors_data.items():
             parsed_new_colors.append({'name': name, 'hex': hex_val})
             
    print(f"Parsed {len(parsed_new_colors)} valid new colors.")

    # 2. Extract existing colors from Kotlin file
    print("Reading existing file...")
    content = read_existing_file(kt_file_path)
    
    pattern = re.compile(r'NamedColor\s*\(\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)')
    existing_matches = pattern.findall(content)
    
    # 3. Merge and Deduplicate
    final_colors = []
    seen_hex = set()
    seen_names = set()
    
    # Keep existing colors (Priority)
    for name, r, g, b in existing_matches:
        r, g, b = int(r), int(g), int(b)
        hex_val = "#{:02x}{:02x}{:02x}".format(r, g, b)
        
        final_colors.append((name, r, g, b))
        seen_hex.add(hex_val)
        seen_names.add(name.lower())
        
    print(f"Found {len(final_colors)} existing colors.")
    
    # Add new colors
    added_count = 0
    for item in parsed_new_colors:
        name = item['name']
        hex_val = item['hex']
        
        # Format Name
        clean_name = name.title()
        
        if hex_val in seen_hex:
            continue
            
        if clean_name.lower() in seen_names:
            continue
            
        try:
            r, g, b = hex_to_rgb(hex_val)
            final_colors.append((clean_name, r, g, b))
            seen_hex.add(hex_val)
            seen_names.add(clean_name.lower())
            added_count += 1
        except:
            pass # Ignore invalid hex
        
    print(f"Added {added_count} new colors.")
    print(f"Total colors: {len(final_colors)}")
    
    # 4. Generate Output
    header = """package com.alexdremov.notate.util

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

object ColorNamer {

    data class NamedColor(val name: String, val r: Int, val g: Int, val b: Int) {
        val colorInt: Int = Color.rgb(r, g, b)
    }

    // A curated list of colors for natural language description.
    // Sources: Standard Web Colors, Material Design, Presets, XKCD, and Color-Name-Lists.
    private val colorList = listOf(
"""

    footer = """    )

    /**
     * Finds the closest natural language name for the given color using Euclidean distance in RGB space.
     * @param color The input color.
     * @return The name of the closest color in the curated list.
     */
    fun getColorName(color: Int): String {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        var minDistance = Double.MAX_VALUE
        var closestColorName = "Unknown"

        for (namedColor in colorList) {
            val distance = calculateDistance(r, g, b, namedColor.r, namedColor.g, namedColor.b)
            if (distance < minDistance) {
                minDistance = distance
                closestColorName = namedColor.name
            }
        }

        return closestColorName
    }

    private fun calculateDistance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        // Simple Euclidean distance
        return sqrt((r2 - r1).toDouble().pow(2) + (g2 - g1).toDouble().pow(2) + (b2 - b1).toDouble().pow(2))
    }
}
"""

    list_items = []
    for name, r, g, b in final_colors:
        # Escape quotes in names just in case
        safe_name = name.replace('"', '\"')
        list_items.append(f'        NamedColor("{safe_name}", {r}, {g}, {b})')
    
    full_content = header + ",\n".join(list_items) + "\n" + footer
    
    with open(kt_file_path, 'w') as f:
        f.write(full_content)
    
    print("Successfully updated ColorNamer.kt")

if __name__ == "__main__":
    main()
