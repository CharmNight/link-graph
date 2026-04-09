package com.charmnight.linkgraph.fixtures.simple;

import external.ExternalList;

public class InterfaceDispatchChain
{
    static class ProjectList implements ExternalList<String>
    {
        @Override
        public boolean add(String element)
        {
            return element != null;
        }
    }

    public void collect()
    {
        ExternalList<String> values = new ProjectList();
        values.add("alpha");
    }
}
