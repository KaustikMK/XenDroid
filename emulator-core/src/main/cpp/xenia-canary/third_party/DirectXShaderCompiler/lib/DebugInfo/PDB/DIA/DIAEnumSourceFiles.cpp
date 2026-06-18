//==- DIxendroidmSourceFiles.cpp - DIA Source File Enumerator impl ---*- C++ -*-==//
//
//                     The LLVM Compiler Infrastructure
//
// This file is distributed under the University of Illinois Open Source
// License. See LICENSE.TXT for details.
//
//===----------------------------------------------------------------------===//

#include "llvm/DebugInfo/PDB/PDBSymbol.h"
#include "llvm/DebugInfo/PDB/DIA/DIxendroidmSourceFiles.h"
#include "llvm/DebugInfo/PDB/DIA/DIASourceFile.h"

using namespace llvm;

DIxendroidmSourceFiles::DIxendroidmSourceFiles(
    const DIASession &PDBSession, CComPtr<IDixendroidmSourceFiles> Dixendroidmerator)
    : Session(PDBSession), Enumerator(Dixendroidmerator) {}

uint32_t DIxendroidmSourceFiles::getChildCount() const {
  LONG Count = 0;
  return (S_OK == Enumerator->get_Count(&Count)) ? Count : 0;
}

std::unique_ptr<IPDBSourceFile>
DIxendroidmSourceFiles::getChildAtIndex(uint32_t Index) const {
  CComPtr<IDiaSourceFile> Item;
  if (S_OK != Enumerator->Item(Index, &Item))
    return nullptr;

  return std::unique_ptr<IPDBSourceFile>(new DIASourceFile(Session, Item));
}

std::unique_ptr<IPDBSourceFile> DIxendroidmSourceFiles::getNext() {
  CComPtr<IDiaSourceFile> Item;
  ULONG NumFetched = 0;
  if (S_OK != Enumerator->Next(1, &Item, &NumFetched))
    return nullptr;

  return std::unique_ptr<IPDBSourceFile>(new DIASourceFile(Session, Item));
}

void DIxendroidmSourceFiles::reset() { Enumerator->Reset(); }

DIxendroidmSourceFiles *DIxendroidmSourceFiles::clone() const {
  CComPtr<IDixendroidmSourceFiles> EnumeratorClone;
  if (S_OK != Enumerator->Clone(&EnumeratorClone))
    return nullptr;
  return new DIxendroidmSourceFiles(Session, EnumeratorClone);
}
