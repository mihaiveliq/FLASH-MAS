/*******************************************************************************
 * Copyright (C) 2018 Andrei Olaru.
 * 
 * This file is part of Flash-MAS. The CONTRIBUTORS.md file lists people who have been previously involved with this project.
 * 
 * Flash-MAS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or any later version.
 * 
 * Flash-MAS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Flash-MAS.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package net.xqhs.flash.core;

import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.xqhs.flash.core.util.ContentHolder;
import net.xqhs.flash.core.util.TreeParameterSet;
import net.xqhs.util.XML.XMLParser;
import net.xqhs.util.XML.XMLTree;
import net.xqhs.util.XML.XMLTree.XMLNode;
import net.xqhs.util.XML.XMLTree.XMLNode.XMLAttribute;
import net.xqhs.util.logging.Logger;
import net.xqhs.util.logging.UnitComponentExt;

/**
 * This class manages deployment configurations. It handles loading the elements of the configuration from various
 * sources -- default values, arguments given to the program, or settings specified in the deployment file.
 * <p>
 * The precedence of values for settings is the following (latter values override former values):
 * <ul>
 * <li>values given in DEFAULTS member;
 * <li>values given in the deployment file.
 * <li>values given as command-line arguments;
 * </ul>
 * <p>
 * The configuration is created as an "entity list" which is a tree (this class itself extends
 * {@link TreeParameterSet}), in which the first level are entity types, and other each type entities are listed by name
 * (if any).
 * 
 * @author Andrei Olaru
 */
public class DeploymentConfiguration extends TreeParameterSet
{
	/**
	 * The class UID.
	 */
	private static final long				serialVersionUID				= 5157567185843194635L;
	
	/**
	 * Prefix of category names used in CLI.
	 */
	public static final String				CLI_CATEGORY_PREFIX				= "-";
	/**
	 * Separator of parts of a name and of parameter and value.
	 */
	public static final String				NAME_SEPARATOR					= ":";
	/**
	 * Separator for elements in the load order setting.
	 */
	public static final String				LOAD_ORDER_SEPARATOR			= " ";
	/**
	 * The name of nodes containing parameters.
	 */
	public static final String				PARAMETER_ELEMENT_NAME			= "parameter";
	/**
	 * The name of the attribute of a parameter node holding the name of the parameter.
	 */
	public static final String				PARAMETER_NAME					= "name";
	/**
	 * The name of the attribute of a parameter node holding the value of the parameter.
	 */
	public static final String				PARAMETER_VALUE					= "value";
	/**
	 * The name of the attribute which contains the name.
	 */
	public static final String				NAME_ATTRIBUTE_NAME				= "name";
	/**
	 * The name of the element(s) which contain entity context.
	 */
	public static final String				CONTEXT_ELEMENT_NAME			= "in-context-of";
	/**
	 * Name of XML nodes for entities other than those in {@link CategoryName}.
	 */
	public static final String				GENERAL_ENTITY_NAME				= "entity";
	/**
	 * The name of the XML attribute specifying the type of the entity.
	 */
	public static final String				GENERAL_ENTITY_TYPE_ATTRIBUTE	= "type";
	/**
	 * The (possibly implicit) root category which can be auto-generated.
	 */
	public static final CategoryName		ROOT_CATEGORY					= CategoryName.NODE;
	/**
	 * The {@value #ROOT_CATEGORY} that was auto-generated, if any.
	 */
	protected TreeParameterSet				autoGeneratedRoot				= null;
	
	/**
	 * Root package for FLASH classes.
	 */
	public static final String				ROOT_PACKAGE					= "net.xqhs.flash";
	/**
	 * Package for core FLASH functionality
	 */
	public static final String				CORE_PACKAGE					= "core";
	/**
	 * The default directory for deployment files.
	 */
	public static final String				DEPLOYMENT_FILE_DIRECTORY		= "src-deployment/";
	
	/**
	 * Default values.
	 */
	public static final Map<String, String>	DEFAULTS						= new HashMap<>();
	
	static
	{
		DEFAULTS.put(CategoryName.SCHEMA.getName(), "src-schema/deployment-schema.xsd");
		DEFAULTS.put(CategoryName.DEPLOYMENT.getName(), DEPLOYMENT_FILE_DIRECTORY +
		
		// "ChatAgents/deployment-chatAgents.xml"
				"ComplexDeployment/deployment-complexDeployment.xml"
		// "scenario/examples/sclaim_tatami2/simpleScenarioE/scenarioE-tATAmI2-plus.xml"
		
		);
		DEFAULTS.put(CategoryName.LOAD_ORDER.getName(), "support agent");
	}
	
	/**
	 * The method loads all available values from the specified sources.
	 * <p>
	 * The only given source is the arguments the program has received, as the name of the deployment file will be
	 * decided by this method. If it is instructed through the parameter, the deployment file is parsed, producing an
	 * additional source of configuration values.
	 * <p>
	 * The <code>load()</code> method can be called only once. It is why all sources must be given in a single call to
	 * <code>load()</code>.
	 * <p>
	 * Therefore, if it is desired to pick <i>any</i> settings from the deployment file, the <code>boolean</code>
	 * argument should be set to <code>true</code>.
	 * 
	 * @param programArguments
	 *            - the arguments passed to the application, exactly as they were passed.
	 * @param parseDeploymentFile
	 *            - if <code>true</code>, the deployment file will be parsed to obtain the setting values placed in the
	 *            deployment; also, the {@link XMLTree} instance resulting from the parsing will be placed as content in
	 *            the last parameter.
	 * @param loadedXML
	 *            - if the deployment file is parsed and this argument is not <code>null</code>, the resulting
	 *            {@link XMLTree} instance will be stored in this ContentHolder instance.
	 * @return the instance itself, which is also the {@link TreeParameterSet} that contains all settings.
	 * 
	 * @throws ConfigLockedException
	 *             - if load() is called more than once.
	 */
	public TreeParameterSet loadConfiguration(List<String> programArguments, boolean parseDeploymentFile,
			ContentHolder<XMLTree> loadedXML) throws ConfigLockedException
	{
		locked();
		UnitComponentExt log = (UnitComponentExt) new UnitComponentExt().setUnitName("settings load");
		
		// ====================================== get default settings
		for(String setting : DEFAULTS.keySet())
			this.add(setting, DEFAULTS.get(setting));
		log.lf("initial tree:", this);
		
		log.lf("program arguments:", programArguments);
		
		// ====================================== get deployment file and schema
		boolean scenarioFirst = false;
		if(programArguments.size() > 0 && programArguments.get(0).length() > 0
				&& !programArguments.get(0).startsWith(CLI_CATEGORY_PREFIX) && !programArguments.get(0).contains(":"))
		{
			set(CategoryName.DEPLOYMENT.getName(), programArguments.get(0));
			scenarioFirst = true;
		}
		else
			for(Iterator<String> it = programArguments.iterator(); it.hasNext();)
			{
				String arg = it.next();
				if(isCategory(arg) && (getCategory(arg).equals(CategoryName.DEPLOYMENT.getName())
						|| getCategory(arg).equals(CategoryName.SCHEMA.getName())))
				{
					String val = null;
					if(it.hasNext() || isCategory(val = it.next()))
						throw new IllegalArgumentException(
								"Program argument after " + arg + " should be a correct value.");
					set(getCategory(arg), val);
				}
			}
		log.lf("loading scenario [] with schema [].", get(CategoryName.DEPLOYMENT.getName()),
				get(CategoryName.SCHEMA.getName()));
		
		// ====================================== load deployment file
		XMLTree XMLtree = XMLParser.validateParse(get(CategoryName.SCHEMA.getName()),
				get(CategoryName.DEPLOYMENT.getName()));
		if(loadedXML != null)
			loadedXML.set(XMLtree);
		if(XMLtree == null)
			log.le("Deployment file load failed.");
		else
		{
			readXML(XMLtree.getRoot(), this, this, log);
			log.lf("after XML tree parse:", this);
			log.lf(">>>>>>>>");
		}
		
		// ====================================== parse CLI args
		Iterator<String> it = programArguments.iterator();
		if(scenarioFirst)
			it.next();
		readCLIArgs(it, this, log);
		log.lf("after CLI tree parse:", this);
		
		// ====================================== port portables
		
		TreeParameterSet allNodes = this.getTree(CategoryName.NODE.getName());
		for(String catName : this.getKeys())
		{
			if(CategoryName.byName(catName) == null || !CategoryName.byName(catName).isPortable())
				continue;
			CategoryName cat = CategoryName.byName(catName);
			for(String node : allNodes.getHierarchicalKeys())
				if(!allNodes.getTree(node).getKeys().contains(catName) || !cat.isUnique())
				{ // if node has no entry for the category or if the category is not unique
					if(this.isSimple(catName))
						allNodes.getTree(node).addAll(catName, this.getValues(catName));
					else
						allNodes.getTree(node).addTrees(catName, this.getTrees(catName));
				}
		}
		log.lf("final config:", this);
		
		log.doExit();
		lock();
		return this;
	}
	
	/**
	 * Recursive method (recursing on XML nodes) which reads data from an XML (sub-)tree from the deployment file into
	 * the given configuration tree. It also:
	 * <ul>
	 * <li>assigns names to entities, potentially auto-generated, based on the rules in {@link CategoryName};
	 * <li>assigns contexts in the tree structure based on the value of the {@value #CONTEXT_ELEMENT_NAME} attributes;
	 * <li>creates {@value #CONTEXT_ELEMENT_NAME} parameters based on tree structure;
	 * <li>adds entities to the entity list, using the actual subtrees in the configuration tree;
	 * </ul>
	 * 
	 * @param node
	 *            - the XML node to read.
	 * @param _nodeTree
	 *            - the configuration sub-tree corresponding to the current node.
	 * @param rootTree
	 *            - the global configuration tree / entity list.
	 * @param log
	 *            - the {@link Logger} to use.
	 */
	protected void readXML(XMLNode node, TreeParameterSet _nodeTree, TreeParameterSet rootTree, UnitComponentExt log)
	{
		// String l = "Node " + node.getName() + " with attributes ";
		// for(XMLAttribute a : node.getAttributes())
		// l += a.getName() + ",";
		// l += " and children ";
		// for(XMLNode n : node.getNodes())
		// l += n.getName() + ",";
		// log.lf(l);
		
		TreeParameterSet nodeTree = _nodeTree;
		if(nodeTree != rootTree)
			// now inside an XML node that has already been integrated.
			for(XMLAttribute a : node.getAttributes())
				nodeTree.add(a.getName(), a.getValue());
			
		// check subordinate XML nodes and integrate them.
		for(XMLNode child : node.getNodes())
		{
			if(child.getName().equals(PARAMETER_ELEMENT_NAME))
				// parameter nodes, add their values to the current tree
				nodeTree.add(child.getAttributeValue(PARAMETER_NAME), child.getAttributeValue(PARAMETER_VALUE));
			else
			{
				// node must be integrated as a different entity
				
				// get information on the child's category
				String catName = child.getName();
				if(catName.equals(GENERAL_ENTITY_NAME))
					catName = child.getAttributeValue(GENERAL_ENTITY_TYPE_ATTRIBUTE);
				CategoryName category = CategoryName.byName(catName);
				if(nodeTree == rootTree)
					if(category == null || (category.getAncestorsList().contains(ROOT_CATEGORY.getName())
							&& !(ROOT_CATEGORY.getName().equals(category.getParent()) && category.isPortable())))
					{ // if no known category or category should be inside a node but is not a node-parented portable
						// category, must create an implicit local node
						nodeTree = rootTree.getTree(ROOT_CATEGORY.getName(), true).getTree(null, true);
						autoGeneratedRoot = nodeTree;
					}
				if(ROOT_CATEGORY.equals(category) && nodeTree == autoGeneratedRoot)
					// if in the auto-generated root but should create another root
					nodeTree = rootTree;
				if(nodeTree == autoGeneratedRoot && category != null
						&& ROOT_CATEGORY.getName().equals(category.getParent()) && category.isPortable())
					// put portable categories declared at the root level in the root level
					nodeTree = rootTree;
				
				if(category != null && category.isUnique() && CategoryName.byName(child.getName()) != null)
					// for unique categories, overwrite any previous setting
					nodeTree.clear(child.getName());
				
				if(child.getNodes().isEmpty() && child.getAttributes().isEmpty()
						&& !nodeTree.isHierarchical(child.getName()))
				{// text node, that will also be treated as parameter - value
					nodeTree.add(child.getName(), (String) child.getValue());
					continue;
				}
				
				// get the child's name or create it according to the child's category / entity.
				String name = getXMLValue(child, NAME_ATTRIBUTE_NAME);
				boolean nameGenerated = name == null;
				if(name == null && category != null && category.hasNameWithParts())
				{ // node has a registered category and its elements have two-parts names
					String[] partNames = category.nameParts();
					String part1 = getXMLValue(child, partNames[0]);
					String part2 = getXMLValue(child, partNames[1]);
					if(part1 == null && !category.isNameSecondPartOptional())
					{
						log.le("Child of [] does not contain necessary name part attribute [].", node.getName(),
								partNames[0]);
						continue;
					}
					name = (part1 != null ? part1 : "") + (part2 != null ? NAME_SEPARATOR + part2 : "");
				}
				if(name != null && name.trim().length() == 0)
					name = null; // no 0-length names
				TreeParameterSet childTree = nodeTree.getTree(catName, true).getTree(name, true);
				if(nameGenerated && !childTree.isSimple(NAME_ATTRIBUTE_NAME) && name != null
						&& name.trim().length() > 0)
					// add name parameter
					childTree.add(NAME_ATTRIBUTE_NAME, name);
				
				// add to entity list if the entity is identifiable
				if(category != null && category.isIdentifiable() && nodeTree != rootTree
						&& !rootTree.getTree(catName, true).isHierarchical(name))
					rootTree.getTree(catName).addTree(name, childTree);
				
				// add context of the entity
				
				// read any other properties of the entity
				readXML(child, childTree, rootTree, log);
			}
		}
	}
	
	/**
	 * Reads data from program arguments into the given configuration tree. The parser attempts to place the parameters
	 * in the correct categories / elements in the already existing tree (read from the XML) or introduce new elements
	 * at the correct places. The CLI arguments are parsed in order as follows:
	 * <ul>
	 * <li>if the argument is a category / entity ("-categ"), its correct place in the tree is found, either by
	 * advancing in the tree or going upwards in the tree until in the correct context.
	 * <li>the category name must be immediately followed by the element name (may be new or existing).
	 * <li>what follows until the next category name are arguments of the form "parameter:value" or just "parameter".
	 * </ul>
	 * <p>
	 * For integrating entities that are not already in the XML, a stack is used to keep track of the current position
	 * in the tree. The "current position" in the tree is decided by the existing tree (for existing elements and
	 * entities), by the hierarchy described in {@link CategoryName}, for known entities, and otherwise each new entity
	 * is considered as subordinate to the previous entity and for known entities the stack is popped until getting to
	 * the level where the entity appeared previously.
	 * 
	 * @param args
	 *            - an {@link Iterator} through the arguments.
	 * @param rootTree
	 *            - the configuration tree. The given tree is expected to already contain the data from the XML
	 *            deployment file.
	 * @param log
	 *            - the {@link Logger} to use.
	 */
	protected static void readCLIArgs(Iterator<String> args, TreeParameterSet rootTree, UnitComponentExt log)
	{
		class CTriple // represents the current node in the tree
		{
			String				category;	// the name of the category
			TreeParameterSet	catTree;	// the subtree of the category, will contain elements in this categories
			TreeParameterSet	elemTree;	// the subtree of the element, will contain parameters or subordinate
											// categories
			
			public CTriple(String cat, TreeParameterSet categoryTree, TreeParameterSet elTree)
			{
				category = cat;
				catTree = categoryTree;
				elemTree = elTree;
			}
			
			@Override
			public String toString()
			{
				return "{" + category + "/" + (catTree != null ? catTree.toString(1, true) : "-") + "/"
						+ (elemTree != null ? elemTree.toString(1, true) : "-") + "}";
			}
		}
		Deque<CTriple> context = new LinkedList<>(); // categories & elements context
		
		while(args.hasNext())
		{
			// log.lf(context.toString());
			String a = args.next();
			if(a.trim().length() == 0)
				continue;
			if(isCategory(a))
			{
				// get category
				String catName = getCategory(a);
				CategoryName category = CategoryName.byName(getCategory(a));
				if(!args.hasNext())
				{ // must check this before creating any trees
					log.lw("Empty unknown category [] in CLI arguments.", catName);
					return;
				}
				String name = args.next();
				
				// create / find the context
				// search upwards in the current context
				// save the current context, in case no appropriate context found upwards
				Deque<CTriple> savedContext = new LinkedList<>(context);
				while(!context.isEmpty())
				{
					if(context.peek().elemTree.isHierarchical(catName))
					{ // found a level that contains the same category; will insert new element here
						context.push(new CTriple(catName, context.peek().elemTree.getTree(catName), null));
						break;
					}
					else if(category != null && category.getParent().equals(context.peek().category))
					{ // category is known and found the correct parent for the category
						TreeParameterSet c = context.peek().elemTree.getTree(catName, true);
						context.push(new CTriple(catName, c, null));
						break;
					}
					// no match yet
					context.pop();
				}
				if(context.isEmpty())
				{
					String msg = "Category [] has parent [] and no instance of parent could be found;";
					if(category == null)
					{ // category not known
						log.lw(msg + " adding in current context.", catName, "unknown");
						context = new LinkedList<>(savedContext);
					}
					else
					{
						if(category.getAncestorsList().contains(ROOT_CATEGORY.getName()))
						{ // must create an implicit instance of the root category
							TreeParameterSet implicitCat = rootTree.getTree(ROOT_CATEGORY.getName());
							TreeParameterSet implicitElem = implicitCat.getTree(null);
							context.push(new CTriple(ROOT_CATEGORY.getName(), implicitCat, implicitElem));
							log.lw(msg + " adding to implicit [] level.", catName, category.getParent(),
									ROOT_CATEGORY.getName());
						}
						if(!category.isParentOptional() && !context.peek().category.equals(category.getParent()))
						{
							log.le(msg + " ignoring other arguments beginning with [].", catName, category.getParent(),
									a);
							return;
						}
						if(context.isEmpty())
							log.lw(msg + " adding to top level.", catName, category.getParent());
					}
					TreeParameterSet c = context.peek().elemTree.getTree(catName, true);
					context.push(new CTriple(catName, c, null));
				}
				
				// the category has been added, now its time to enter or create the element
				context.peek().elemTree = context.peek().catTree.getTree(name, true);
				
				// add to entity list if the entity is identifiable
				if(context.size() > 1)
					if(category != null && category.isIdentifiable()
							&& !rootTree.getTree(catName, true).isHierarchical(name))
						// if not already in entity list
						rootTree.getTree(catName).addTree(name, context.peek().elemTree);
			}
			else
			{
				if(context.peek().elemTree == null)
				{
					log.le("incorrect context for argument []", a);
					continue;
				}
				String parameter, value = null;
				if(a.contains(NAME_SEPARATOR))
				{ // parameter name & value
					String[] es = a.split(NAME_SEPARATOR, 2);
					parameter = es[0];
					value = es[1];
				}
				else
					parameter = a;
				context.peek().elemTree.add(parameter, value);
			}
		}
	}
	
	/**
	 * Checks if the given command line argument designates a category (begins with {@value #CLI_CATEGORY_PREFIX}).
	 * 
	 * @param arg
	 *            - the argument.
	 * @return <code>true</code> if it designates a category.
	 */
	protected static boolean isCategory(String arg)
	{
		return arg.startsWith(CLI_CATEGORY_PREFIX);
	}
	
	/**
	 * Returns the actual category name designated by a command line argument (removes preceding
	 * {@value #CLI_CATEGORY_PREFIX})
	 * 
	 * @param arg
	 *            - the argument.
	 * @return the category name.
	 */
	protected static String getCategory(String arg)
	{
		return isCategory(arg) ? arg.substring(1) : null;
	}
	
	/**
	 * Method to simplify the access to a parameter/attribute of an parametric XML node.
	 * <p>
	 * Having the {@link XMLNode} instance associated with the parametric node, the method retrieves the first value
	 * found among the following:
	 * <ul>
	 * <li>the value of the attribute with the searched name, if any, or otherwise
	 * <li>the value associated with the first occurrence of the desired parameter name, if any, or otherwise
	 * <li>the value in the first node with the searched name, if any.
	 * </ul>
	 * 
	 * @param node
	 *            - the node containing the configuration information for the agent.
	 * @param searchName
	 *            - the name of the searched attribute / parameter / node.
	 * @return the value associated with the searched name, or <code>null</code> if nothing found.
	 */
	public static String getXMLValue(XMLNode node, String searchName)
	{
		if(node.getAttributeValue(searchName) != null)
			// from an attribute
			return node.getAttributeValue(searchName);
		if(node.getAttributeOfFirstNodeWithValue(PARAMETER_ELEMENT_NAME, PARAMETER_NAME, searchName,
				PARAMETER_VALUE) != null)
			// from a parameter (e.g. <parameter name="search" value="the name">)
			return node.getAttributeOfFirstNodeWithValue(PARAMETER_ELEMENT_NAME, PARAMETER_NAME, searchName,
					PARAMETER_VALUE);
		if(node.getNode(searchName, 0) != null)
			return node.getNode(searchName, 0).getValue().toString();
		return null;
	}
	
}
