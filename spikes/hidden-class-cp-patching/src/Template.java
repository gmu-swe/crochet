// A tiny, CROCHET-flavored "template" source. We don't actually *use* this
// compiled class — Template.java only exists so you can eyeball the shape of
// what SpecializerB generates. The real template is built from bytecode in
// TemplateBytes.buildTemplate() so we control CP layout exactly like the
// original CheckpointRollbackStubClassGenerator does.
//
// Pseudo shape of the template:
//
//   public class _ANON_CLASS_NAME_ extends _ORIG_CLASS_NAME_ implements _ANON_IFACE_NAME_ {
//     public Object $$crijCheckpoint() {
//       return _ORIG_CLASS_NAME_.__transition_to_on_checkpoint__(); // returns
//                                                                  // specialized marker
//     }
//     public Object $$crijRollback()   { /* likewise */ }
//   }
//
// SpecializerB then patches in the specific user's superclass, the runtime
// state-object reference, and the specific method names per RollbackState.
public class Template { /* placeholder */ }
