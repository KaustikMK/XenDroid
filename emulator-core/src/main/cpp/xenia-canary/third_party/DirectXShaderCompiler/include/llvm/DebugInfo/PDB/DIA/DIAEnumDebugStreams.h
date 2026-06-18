//==- DIxendroidmDebugStreams.h - DIA Debug Stream Enumerator impl ---*- C++ -*-==//
//
//                     The LLVM Compiler Infrastructure
//
// This file is distributed under the University of Illinois Open Source
// License. See LICENSE.TXT for details.
//
//===----------------------------------------------------------------------===//

#ifndef LLVM_DEBUGINFO_PDB_DIA_DIxendroidMDEBUGSTREAMS_H
#define LLVM_DEBUGINFO_PDB_DIA_DIxendroidMDEBUGSTREAMS_H

#include "DIASupport.h"
#include "llvm/DebugInfo/PDB/IPDBEnumChildren.h"

namespace llvm {

class IPDBDataStream;

class DIxendroidmDebugStreams : public IPDBEnumChildren<IPDBDataStream> {
public:
  explicit DIxendroidmDebugStreams(CComPtr<IDixendroidmDebugStreams> Dixendroidmerator);

  uint32_t getChildCount() const override;
  ChildTypePtr getChildAtIndex(uint32_t Index) const override;
  ChildTypePtr getNext() override;
  void reset() override;
  DIxendroidmDebugStreams *clone() const override;

private:
  CComPtr<IDixendroidmDebugStreams> Enumerator;
};
}

#endif
