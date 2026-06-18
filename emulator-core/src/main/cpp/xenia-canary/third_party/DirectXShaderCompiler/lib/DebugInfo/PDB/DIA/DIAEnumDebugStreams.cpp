//==- DIxendroidmDebugStreams.cpp - DIA Debug Stream Enumerator impl -*- C++ -*-==//
//
//                     The LLVM Compiler Infrastructure
//
// This file is distributed under the University of Illinois Open Source
// License. See LICENSE.TXT for details.
//
//===----------------------------------------------------------------------===//

#include "llvm/DebugInfo/PDB/PDBSymbol.h"
#include "llvm/DebugInfo/PDB/DIA/DIADataStream.h"
#include "llvm/DebugInfo/PDB/DIA/DIxendroidmDebugStreams.h"

using namespace llvm;

DIxendroidmDebugStreams::DIxendroidmDebugStreams(
    CComPtr<IDixendroidmDebugStreams> Dixendroidmerator)
    : Enumerator(Dixendroidmerator) {}

uint32_t DIxendroidmDebugStreams::getChildCount() const {
  LONG Count = 0;
  return (S_OK == Enumerator->get_Count(&Count)) ? Count : 0;
}

std::unique_ptr<IPDBDataStream>
DIxendroidmDebugStreams::getChildAtIndex(uint32_t Index) const {
  CComPtr<IDixendroidmDebugStreamData> Item;
  VARIANT VarIndex;
  VarIndex.vt = VT_I4;
  VarIndex.lVal = Index;
  if (S_OK != Enumerator->Item(VarIndex, &Item))
    return nullptr;

  return std::unique_ptr<IPDBDataStream>(new DIADataStream(Item));
}

std::unique_ptr<IPDBDataStream> DIxendroidmDebugStreams::getNext() {
  CComPtr<IDixendroidmDebugStreamData> Item;
  ULONG NumFetched = 0;
  if (S_OK != Enumerator->Next(1, &Item, &NumFetched))
    return nullptr;

  return std::unique_ptr<IPDBDataStream>(new DIADataStream(Item));
}

void DIxendroidmDebugStreams::reset() { Enumerator->Reset(); }

DIxendroidmDebugStreams *DIxendroidmDebugStreams::clone() const {
  CComPtr<IDixendroidmDebugStreams> EnumeratorClone;
  if (S_OK != Enumerator->Clone(&EnumeratorClone))
    return nullptr;
  return new DIxendroidmDebugStreams(EnumeratorClone);
}
