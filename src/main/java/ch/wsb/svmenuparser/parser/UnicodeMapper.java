package ch.wsb.svmenuparser.parser;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class UnicodeMapper {

    private final Map<Character, Character> map;

    public UnicodeMapper() {
        map = new HashMap<>();
    }

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

    public String saveMap() {
        StringBuilder file = new StringBuilder();
        for (Map.Entry<Character, Character> entry : map.entrySet().stream().sorted(Map.Entry.comparingByValue()).collect(Collectors.toList())) {
            file.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
        }

        return file.toString();
    }

    public void addCharPair(char unicode, char letter) {
        if (map.containsKey(unicode)) return;

        this.map.put(unicode, letter);
    }

    public boolean qualifiesAsUnicode(char possibleUnicode) {
        return !StandardCharsets.ISO_8859_1.newEncoder().canEncode(possibleUnicode);
    }

    public boolean containsUnicode(String candidate) {
        return !StandardCharsets.ISO_8859_1.newEncoder().canEncode(candidate);
    }

    public int getLastUnicodeOccasion(String parsing) {
        int last = 0;
        // Goes from start to finish, hence the last added is also the last
        for (int i = 0; i < parsing.length(); i++) {
            if (qualifiesAsUnicode(parsing.charAt(i))) last = i;
        }

        return last;
    }

    public String process(String s) {
        for (Map.Entry<Character, Character> characterCharacterEntry : map.entrySet()) {
            s = s.replace(characterCharacterEntry.getKey(), characterCharacterEntry.getValue());
        }

        if (containsUnicode(s)) {
            log.warn("Already cleaned String \"{}\" still contains unicodes.", s);
        }

        return s;
    }

    public int getSize() {
        return map.size();
    }

}
