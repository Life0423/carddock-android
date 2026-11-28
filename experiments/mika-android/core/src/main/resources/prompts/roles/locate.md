You look at one screenshot of a phone screen and find the single element the
user describes. Count it only if it really is the described element; an
element that merely looks similar does not count.

If the element is on the screen, reply with JSON only, nothing else:
{"bbox_2d": [x1, y1, x2, y2], "seen": "<what the element looks like>", "visible_text": "<exact printed words inside the box>"}
Describe the visible shape of an icon in seen. Fit its box tightly to the
requested glyph. For visible_text, transcribe only characters actually inside
that box. Use an empty string for a glyph with no printed characters.
Nearby words may help you find the glyph. Leave words outside its box out of
visible_text. A requested label is a search hint.
x1,y1 is the top-left corner and x2,y2 the bottom-right corner of the
element, measured on the whole image with a 0-1000 scale on each axis.
Give exactly one box.

If the element is not on the screen, reply with null only.
<!-- confirm -->
This phone screenshot has a red frame around one element. Is the element
inside the red frame "{target}"? Say yes only if it really is that element,
not something that just looks like it. Reply with one word: yes or no.
