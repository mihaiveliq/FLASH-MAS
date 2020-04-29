package interfaceGenerator;

import interfaceGenerator.types.ElementType;
import interfaceGenerator.types.PortType;

import java.util.*;

public class Element {
    private String id;
    private List<Element> children = new ArrayList<>();
    private String type = ElementType.BLOCK.type;
    private Map<String, String> properties = new HashMap<>();
    /*
        area for active input ports
     */
    private static HashMap<String, List<Element>> activePortsWithElements = new HashMap<>();
    private String port;
    private String role;

    public List<Element> getChildren() {
        return children;
    }

    public void setChildren(List<Element> children) {
        this.children = children;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    private static int counter = 0;
    private static Set<String> activePorts = new HashSet<>();
    private static Set<String> ports = new HashSet<>();
    private static boolean checkedActivePorts = false;
    private String value;

    public static void checkActivePorts(Element element) {
        if (element.getPort() != null) {
            if (element.getRole().equals(PortType.ACTIVE.type)) {
                activePorts.add(element.getPort());
            }
        }

        if (element.getChildren() != null) {
            for (var child : element.getChildren()) {
                checkActivePorts(child);
            }
        }
    }

    public static void checkActivePortsWithElement(Element element) {
        if (!checkedActivePorts) {
            checkActivePorts(element);
            checkedActivePorts = true;
        }

        if (element.getPort() != null) {
            if (activePorts.contains(element.getPort())) {
                if (activePortsWithElements.containsKey(element.getPort())) {
                    var value = activePortsWithElements.get(element.getPort());
                    value.add(element);
                    activePortsWithElements.put(element.getPort(), value);
                } else {
                    activePorts.add(element.getPort());
                    activePortsWithElements.put(element.getPort(), new ArrayList<>(Collections.singletonList(element)));
                }
            }
        }

        if (element.getChildren() != null) {
            for (var child : element.getChildren()) {
                checkActivePortsWithElement(child);
            }
        }
    }


    public static HashMap<String, List<Element>> getActivePortsWithElements() {
        return activePortsWithElements;
    }

    public static String identifyActivePortOfElement(String id) {
        System.out.println(id);
        System.out.println(activePortsWithElements);
        for (var entry : activePortsWithElements.entrySet()) {
            var port = entry.getKey();
            for (var element : entry.getValue()) {
                if (id.equals(element.getId())) {
                    return port;
                }
            }
        }
        return null;
    }

    public static String findActiveInputIdFromPort(String port) {
        for (var entry : activePortsWithElements.entrySet()) {
            if (port.equals(entry.getKey())) {
                for (var element : entry.getValue()) {
                    if (element.getType().equals(ElementType.FORM.type)
                            || element.getType().equals(ElementType.SPINNER.type)) {
                        return element.getId();
                    }
                }
            }
        }
        return null;
    }


    public static List<Element> findElementsByPort(Element element, String portName) {
        List<Element> elements = new ArrayList<>();
        if (element.getPort() != null) {
            if (element.getPort().equals(portName)) {
                elements.add(element);
            }
        }
        if (element.getChildren() != null) {
            for (var child : element.getChildren()) {
                elements.addAll(findElementsByPort(child, portName));
            }
        }
        return elements;
    }

    @Override
    public String toString() {
        String tab = "\t";
        StringBuilder result = new StringBuilder();
        result.append(tab.repeat(counter)).append("id: ").append(id).append('\n');
        result.append(tab.repeat(counter)).append("type: ").append(type).append('\n');
        result.append(tab.repeat(counter)).append("port: ").append(port).append('\n');
        result.append(tab.repeat(counter)).append("role: ").append(role).append('\n');
        result.append(tab.repeat(counter)).append("children: ");
        if (children != null) {
            if (children.isEmpty()) {
                result.append("[]").append('\n');
            } else {
                result.append('\n');
                ++Element.counter;
                for (var child : children) {
                    result.append(child.toString());
                }
                --Element.counter;
            }
        }
        result.append('\n');
        return result.toString();
    }

    public static List<String> findActiveInputIdsFromPort(String port) {
        List<String> list = new ArrayList<>();
        for (var entry : activePortsWithElements.entrySet()) {
            if (port.equals(entry.getKey())) {
                for (var element : entry.getValue()) {
                    if (element.getType().equals(ElementType.FORM.type)
                            || element.getType().equals(ElementType.SPINNER.type)) {
                        list.add(element.getId());
                    }
                }
            }
        }
        return list;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public static List<Element> findElementsByRole(Element element, String role) {
        List<Element> elements = new ArrayList<>();

        if (element.getRole() != null && element.getRole().equals(role)) {
            elements.add(element);
        }

        if (element.getChildren() != null) {
            for (var child : element.getChildren()) {
                elements.addAll(findElementsByRole(child, role));
            }
        }

        return elements;
    }

}
