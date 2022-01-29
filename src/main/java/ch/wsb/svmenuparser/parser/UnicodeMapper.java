package ch.wsb.svmenuparser.parser;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * This class is used to map a specific unicode character with its corresponding real character.
 * This is used instead of OCR to read a really weird font the SV-Group decided to use in their menu plans.
 */
@Slf4j
public class UnicodeMapper {

    private final Map<Character, Character> map;

    /**
     * Creates a unicode mapper
     */
    public UnicodeMapper() {
        map = new HashMap<>();
    }

    /**
     * Loads a map from a string.
     * Each unicode, character pair is on its own line, separated by a string.
     * For example, to map the totally weird unicode 'd' for 'a', the following could be used;
     * <p>
     * d a
     *
     * @param map
     */
    public void loadMap(String map) {
        try {
            String[] lines = map.split("\n");
            for (String line : lines) {
                String[] chars = line.split(" ");
                this.map.put(chars[0].charAt(0), chars[1].charAt(0));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.warn("Could not load character map, file is formatted wrongly.");
        }

        log.debug("Successfully loaded a character map holding {} characters", this.map.size());
    }

    /**
     * Saves the map to a string that can be saved to a file
     *
     * @return map as a string
     */
    public String saveMap() {
        StringBuilder file = new StringBuilder();
        for (Map.Entry<Character, Character> entry : map.entrySet().stream().sorted(Map.Entry.comparingByValue()).collect(Collectors.toList())) {
            file.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }

        return file.toString();
    }

    /**
     * Adds a character pair to the map
     *
     * @param unicode unicode to add
     * @param letter  corresponding readable letter
     */
    public void addCharPair(char unicode, char letter) {
        if (map.containsKey(unicode)) return;

        this.map.put(unicode, letter);
    }

    /**
     * Returns whether a character qualifies as a unicode, = if it does not comply with the latin-1 charset
     *
     * @param possibleUnicode possible unicode character
     * @return whether is a unicode
     */
    public boolean qualifiesAsUnicode(char possibleUnicode) {
        return !StandardCharsets.ISO_8859_1.newEncoder().canEncode(possibleUnicode);
    }

    /**
     * Returns whether a string contains a unicode, as defined in #qualifiesAsUnicode(char)
     *
     * @param candidate candidate to check
     * @return whether containing unicode
     */
    public boolean containsUnicode(String candidate) {
        return !StandardCharsets.ISO_8859_1.newEncoder().canEncode(candidate);
    }

    /**
     * Returns the last occasion of a unicode in a string
     *
     * @param parsing string to check
     * @return last index of a unicode (0 if none)
     */
    public int getLastUnicodeOccasion(String parsing) {
        int last = 0;
        // Goes from start to finish, hence the last added is also the last
        for (int i = 0; i < parsing.length(); i++) {
            if (qualifiesAsUnicode(parsing.charAt(i))) last = i;
        }

        return last;
    }

    /**
     * Processes a string though a map, replacing the all unicodes with their letter counterparts
     *
     * @param s string to process
     * @return cleaned string
     */
    public String process(String s) {
        for (Map.Entry<Character, Character> characterCharacterEntry : map.entrySet()) {
            s = s.replace(characterCharacterEntry.getKey(), characterCharacterEntry.getValue());
        }

        if (containsUnicode(s)) {
            log.warn("Already cleaned String \"{}\" still contains unicodes.", s);
        }

        return s;
    }

    /**
     * Returns the size of the map
     *
     * @return size of the map in number of entries
     */
    public int getSize() {
        return map.size();
    }

    /**
     * Opens a cli that can be used for map creation
     *
     * @throws IOException failed to load or save file, if chosen
     */
    public static void evokeCreationCli() throws IOException {
        UnicodeMapper map = new UnicodeMapper();
        Scanner s = new Scanner(System.in);

        System.out.print("\nLoad an existing map from file? (y/n)\n  > ");
        if (s.nextLine().equals("y")) {
            String content = new Scanner(new File("out/map.map")).useDelimiter("\\Z").next();
            map.loadMap(content);
            System.out.printf("Loaded %d characters from out/map.map", map.getSize());
        }

        while (true) {
            System.out.print("\nNext Take (enter exit to close):\nEnter the unicode combo of a known phrase:\n  > ");
            String input = s.nextLine();
            if (input.equals("exit")) break;
            System.out.print("Type out the real phrase:\n  > ");
            String mapped = s.nextLine();

            if (mapped.length() != input.length()) {
                System.out.println("The both inputs are not the same length. Please try again.");
                continue;
            }

            int amount = 0;
            char[] chars = input.toCharArray();
            char[] maps = mapped.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                char target = chars[i];
                if (!map.qualifiesAsUnicode(target)) continue;

                map.addCharPair(target, maps[i]);
                amount++;
            }

            System.out.printf("Great, added %d more characters.\n", amount);
        }

        System.out.print("\nOk. Save map to file? (y/n)\n  > ");
        if (s.nextLine().equals("y")) {
            System.out.println("Writing map to out/map.map");

            FileWriter writer = new FileWriter("out/map.map");
            writer.write(map.saveMap());
            writer.close();
        }

        System.out.println("Done.");
    }

}
