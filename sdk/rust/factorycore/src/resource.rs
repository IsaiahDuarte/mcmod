/// What a [`Resource`] selects.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(i32)]
pub enum ResourceKind {
    /// Items with one registry ID (any components).
    Item = 0,
    /// Fluids with one registry ID.
    Fluid = 1,
    /// Forge Energy (FE).
    Energy = 2,
    /// Items in one item tag.
    ItemTag = 3,
}

/// A resource selector. IDs without a namespace use `minecraft:`, so `item("raw_iron")` and
/// `item("minecraft:raw_iron")` are equal. Build one with [`item`], [`fluid`], [`item_tag`] or
/// [`ENERGY`].
#[derive(Clone, Copy, Debug)]
pub struct Resource<'a> {
    kind: ResourceKind,
    id: &'a str,
}

/// Items with this registry ID, e.g. `item("raw_iron")` or `item("create:brass_ingot")`.
pub const fn item(id: &str) -> Resource<'_> {
    Resource {
        kind: ResourceKind::Item,
        id,
    }
}

/// Fluids with this registry ID, e.g. `fluid("water")`.
pub const fn fluid(id: &str) -> Resource<'_> {
    Resource {
        kind: ResourceKind::Fluid,
        id,
    }
}

/// Items in this item tag, without `#`, e.g. `item_tag("logs")`.
pub const fn item_tag(id: &str) -> Resource<'_> {
    Resource {
        kind: ResourceKind::ItemTag,
        id,
    }
}

/// Forge Energy (FE).
pub const ENERGY: Resource<'static> = Resource {
    kind: ResourceKind::Energy,
    id: "",
};

impl<'a> Resource<'a> {
    /// What this selects.
    pub const fn kind(&self) -> ResourceKind {
        self.kind
    }

    /// The ID exactly as written (empty for [`ENERGY`]).
    pub const fn id(&self) -> &'a str {
        self.id
    }

    /// `(namespace, path)`, defaulting the namespace to `minecraft`.
    pub fn namespaced(&self) -> (&'a str, &'a str) {
        match self.id.split_once(':') {
            Some((namespace, path)) => (namespace, path),
            None => ("minecraft", self.id),
        }
    }
}

impl PartialEq for Resource<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.kind == other.kind
            && (self.kind == ResourceKind::Energy || self.namespaced() == other.namespaced())
    }
}

impl Eq for Resource<'_> {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_default_to_the_minecraft_namespace() {
        assert_eq!(item("raw_iron"), item("minecraft:raw_iron"));
        assert_eq!(
            item("create:brass_ingot").namespaced(),
            ("create", "brass_ingot")
        );
        assert_ne!(item("water"), fluid("water"));
        assert_ne!(item("logs"), item_tag("logs"));
        assert_ne!(item("a:x"), item("b:x"));
        assert_eq!(ENERGY, ENERGY);
    }
}
