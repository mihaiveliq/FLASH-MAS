package net.xqhs.flash.gui.structure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ElementIdManager {
	protected Map<String, Integer> idCounter = new HashMap<>();
	
	protected Map<String, Element>	idToElement	= new HashMap<>();
	protected Map<String, String>	idToEntity	= new HashMap<>();
	
	public String makeID(String entity, String port, String role) {
		return (entity != null ? entity + "_" : "") + port + "_" + role + "_";
	}
	
	public List<String> getIDs(String entity, String port, String role) {
		String prefix = makeID(entity, port, role);
		List<String> result = new LinkedList<>();
		if(!idCounter.containsKey(prefix))
			return result;
		for(int i = 0; i <= idCounter.get(prefix).intValue(); i++)
			result.add(prefix + i);
		return result;
	}
	
	protected String newID(String entity, String port, String role) {
		String result = makeID(entity, port, role);
		if(idCounter.containsKey(result)) {
			int count = idCounter.get(result);
			idCounter.put(result, ++count);
			result += count;
		}
		else {
			idCounter.put(result, 0);
			result += 0;
		}
		return result;
	}
	
	protected void insertIdInto(Element element, String entity) {
		element.setId(newID(entity, element.getPort(), element.getRole()));
		idToElement.put(element.getId(), element);
		if(entity != null)
			idToEntity.put(element.getId(), entity);
	}
	
	public Element insertIdsInto(Element element) {
		insertIdInto(element, null);
		if(element.getChildren() != null && !element.getChildren().isEmpty()) {
			for(int i = 0; i < element.getChildren().size(); i++) {
				element.getChildren().set(i, insertIdsInto(element.getChildren().get(i)));
			}
		}
		return element;
	}
	
	public Element insertIdsInto(Element element, String entity) {
		insertIdInto(element, entity);
		if(element.getChildren() != null && !element.getChildren().isEmpty()) {
			for(int i = 0; i < element.getChildren().size(); i++) {
				element.getChildren().set(i, insertIdsInto(element.getChildren().get(i), entity));
			}
		}
		return element;
	}
	
	public void removeIdsWithPrefix(String prefix) {
		Set<String> toRemove = new HashSet<>();
		for(String key : idCounter.keySet())
			if(key.startsWith(prefix))
				toRemove.add(key);
		for(String key : toRemove) {
			idCounter.remove(key);
			idToElement.remove(key);
		}
	}
	
	public Element getElement(String id) {
		return idToElement.get(id);
	}
	
	public String getEntity(String id) {
		return idToEntity.get(id);
	}
}
