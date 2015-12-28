package org.opencypher.grammar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.xml.parsers.ParserConfigurationException;

import org.opencypher.tools.xml.Attribute;
import org.opencypher.tools.xml.Child;
import org.opencypher.tools.xml.Element;
import org.opencypher.tools.xml.XmlParser;
import org.xml.sax.SAXException;

import static java.util.Objects.requireNonNull;

import static org.opencypher.tools.xml.XmlParser.xmlParser;

@Element(uri = Grammar.XML_NAMESPACE, name = "grammar")
class Root implements Iterable<Production>
{
    enum ResolutionOption
    {
        ALLOW_ROOTLESS, SKIP_UNUSED_PRODUCTIONS
    }

    static final XmlParser<Root> XML = xmlParser( Root.class );

    @Attribute
    String language;
    @Attribute(name = "case-sensitive", optional = true)
    boolean caseSensitive = true;
    private final Map<String, Production> productions = new LinkedHashMap<>();

    @Child
    void add( Production production )
    {
        if ( productions.put( production.name, production ) != null )
        {
            throw new IllegalArgumentException( "Duplicate definition of '" + production.name + "' production" );
        }
    }

    @Child
    void addVocabulary( VocabularyReference vocabulary ) throws ParserConfigurationException, SAXException, IOException
    {
        for ( Production production : vocabulary.resolve() )
        {
            add( production );
        }
    }

    final Grammar resolve( Function<Map<String, Production>, Map<String, Production>> copy, ResolutionOption... config )
    {
        Set<ResolutionOption> options = EnumSet.noneOf( ResolutionOption.class );
        if ( config != null )
        {
            Collections.addAll( options, config );
        }
        Dependencies dependencies = new Dependencies();
        Set<String> unused = new HashSet<>( productions.keySet() );
        // find the root production
        if ( !unused.remove( language ) )
        {
            if ( options.contains( ResolutionOption.ALLOW_ROOTLESS ) )
            {
                productions.values().stream()
                           .filter( production -> Objects.equals( production.vocabulary, language ) )
                           .forEach( production -> unused.remove( production.name ) );
            }
            else
            {
                dependencies.missingProduction( language, new Production( this ) );
            }
        }
        // Resolve non-terminals in all productions
        Function<String, Production> lookup = ( name ) -> {
            unused.remove( name );
            return productions.get( name );
        };
        for ( Production production : productions.values() )
        {
            production.resolve( lookup, dependencies );
        }
        // check for errors
        dependencies.reportMissingProductions();
        // filter out unused productions
        if ( !unused.isEmpty() )
        {
            if ( options.contains( ResolutionOption.SKIP_UNUSED_PRODUCTIONS ) )
            {
                while ( false && !unused.isEmpty() )
                {
                    for ( String name : new ArrayList<>( unused ) )
                    {
                        Production production = productions.remove( name );
                    }
                }
            }
            else
            {
                System.err.println( "WARNING! Unused productions:" );
                for ( String name : unused )
                {
                    System.err.println( "\t" + name );
                }
            }
        }
        return new Grammar( this, copy.apply( productions ) );
    }

    @Override
    public Iterator<Production> iterator()
    {
        return productions.values().iterator();
    }

    private static final class Grammar implements org.opencypher.grammar.Grammar
    {
        private final String language;
        private final Map<String, Production> productions;
        private final boolean caseSensitive;

        Grammar( Root root, Map<String, Production> productions )
        {
            this.language = requireNonNull( root.language, "language" );
            this.caseSensitive = root.caseSensitive;
            this.productions = productions;
        }

        @Override
        public String language()
        {
            return language;
        }

        @Override
        public boolean caseSensitiveByDefault()
        {
            return caseSensitive;
        }

        @Override
        public String productionDescription( String name )
        {
            Production production = productions.get( name );
            if ( production == null )
            {
                throw new NoSuchElementException();
            }
            return production.description;
        }

        @Override
        public <EX extends Exception> void accept( GrammarVisitor<EX> visitor ) throws EX
        {
            for ( Production production : productions.values() )
            {
                production.accept( visitor );
            }
        }

        @Override
        public String toString()
        {
            return "Grammar{" + language + "}";
        }

        @Override
        public int hashCode()
        {
            return language.hashCode();
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != Grammar.class )
            {
                return false;
            }
            Grammar that = (Grammar) obj;
            return this.caseSensitive == that.caseSensitive &&
                   language.equals( that.language ) &&
                   productions.equals( that.productions );
        }
    }
}
