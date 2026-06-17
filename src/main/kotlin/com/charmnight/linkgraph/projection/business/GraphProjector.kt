package com.charmnight.linkgraph.projection.business

/**
 * Marker for projectors that turn a domain input (semantic analysis result, architecture
 * index, usage search result, review evidence bundle, ...) into a view document or other
 * presentation shape ready for UI consumption.
 *
 * The implementations in this package vary widely in input/output types, so the interface
 * carries no generics — its purpose is to make the "I am a business projector" role
 * discoverable for navigation and future tooling, not to enforce a uniform signature.
 */
interface GraphProjector
