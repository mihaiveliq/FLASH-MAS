package interfaceGenerator.types;

public enum PortType {
    ACTIVE("activate"),
    PASSIVE("passive"),
    CONTENT("content"),
    OUTPUT("output"),
    EXTENDED_INTERFACES("extended-interfaces"),
    ENTITIES("entities");

    public final String type;

    PortType(String type) {
        this.type = type;
    }

    public static PortType valueOfLabel(String type) {
        for (PortType e : values()) {
            if (e.type.equals(type)) {
                return e;
            }
        }
        return null;
    }
}
